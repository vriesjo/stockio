package nl.stockio.stock.infra

import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.StockSyncResult
import nl.stockio.stock.domain.port.StockLedgerBusyException
import nl.stockio.stock.domain.port.StockLedgerProgress
import nl.stockio.stock.domain.port.StockLedgerRun
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import nl.stockio.stock.domain.port.StockLedgerSyncStatusRepository
import nl.stockio.stock.domain.port.StockReceiptFollowup
import nl.stockio.stock.domain.port.StockScopeRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class StockLedgerRunJdbcRepository(
    private val jdbc: JdbcTemplate,
    private val scopes: StockScopeRepository,
    private val syncStatus: StockLedgerSyncStatusRepository,
    private val clock: Clock
) : StockLedgerRunRepository {
    @Transactional
    override fun begin(snapshotDate: LocalDate, owner: UUID, now: Instant): StockLedgerRun {
        val control = jdbc.queryForMap("SELECT * FROM stock_ledger_coordinator WHERE id = 1 FOR UPDATE")
        val lease = (control["lease_until"] as Timestamp?)?.toInstant()
        if (control["owner"] != null && lease != null && lease > now) throw StockLedgerBusyException()
        val previousId = control["run_id"] as UUID?
        val previous = previousId?.let {
            jdbc.queryForMap("SELECT * FROM stock_ledger_run WHERE id = ?", it)
        }
        val resumable = previous != null && previous["status"] in setOf("RUNNING", "PARTIAL") &&
            (previous["snapshot_date"] as Date).toLocalDate() == snapshotDate
        val runId = if (resumable) {
            checkNotNull(previousId).also {
                jdbc.update("UPDATE stock_ledger_run SET status = 'RUNNING', last_error = NULL WHERE id = ?", it)
            }
        } else {
            if (previous != null && previous["status"] in setOf("RUNNING", "PARTIAL")) {
                jdbc.update("UPDATE stock_ledger_run SET status = 'SUPERSEDED' WHERE id = ?", previousId)
            }
            UUID.randomUUID().also { id ->
                jdbc.update(
                    "INSERT INTO stock_ledger_run (id, snapshot_date, status, created_at) VALUES (?, ?, 'RUNNING', ?)",
                    id, snapshotDate, Timestamp.from(now)
                )
                scopes.findSkus().forEach { sku ->
                    jdbc.update("INSERT INTO stock_ledger_work (run_id, sku) VALUES (?, ?)", id, sku)
                }
            }
        }
        jdbc.update(
            "UPDATE stock_ledger_coordinator SET run_id = ?, owner = ?, lease_until = ? WHERE id = 1",
            runId, owner, Timestamp.from(now.plus(LEASE))
        )
        return StockLedgerRun(
            runId, snapshotDate,
            jdbc.queryForList(
                "SELECT sku FROM stock_ledger_work WHERE run_id = ? AND completed_at IS NULL ORDER BY sku",
                String::class.java, runId
            )
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockWork(runId: UUID, sku: String, owner: UUID): Boolean {
        // Acquired after the SKU, never while waiting for it. Fences an expired coordinator.
        checkOwner(runId, owner)
        return jdbc.queryForMap(
            "SELECT completed_at FROM stock_ledger_work WHERE run_id = ? AND sku = ? FOR UPDATE", runId, sku
        )["completed_at"] == null
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun completeWork(
        runId: UUID, sku: String, owner: UUID, skuCount: Int, lotCount: Int, now: Instant
    ) {
        check(jdbc.update(
            "UPDATE stock_ledger_work SET completed_at = ?, sku_count = ?, lot_count = ? " +
                "WHERE run_id = ? AND sku = ? AND completed_at IS NULL",
            Timestamp.from(now), skuCount, lotCount, runId, sku
        ) == 1) { "Ledger work is missing or already complete." }
        jdbc.update(
            "UPDATE stock_ledger_coordinator SET lease_until = ? WHERE id = 1 AND run_id = ? AND owner = ?",
            Timestamp.from(now.plus(LEASE)), runId, owner
        )
    }

    @Transactional
    override fun complete(runId: UUID, owner: UUID, now: Instant): StockSyncResult {
        checkOwner(runId, owner)
        val counts = jdbc.queryForMap(
            "SELECT COUNT(*) FILTER (WHERE completed_at IS NULL) AS pending, " +
                "COALESCE(SUM(sku_count), 0) AS skus, COALESCE(SUM(lot_count), 0) AS lots " +
                "FROM stock_ledger_work WHERE run_id = ?", runId
        )
        check((counts["pending"] as Number).toInt() == 0) { "Ledger work remains incomplete." }
        val date = jdbc.queryForObject(
            "SELECT snapshot_date FROM stock_ledger_run WHERE id = ?", LocalDate::class.java, runId
        )!!
        require(date == LocalDate.now(clock)) { "Ledger completion crossed the business-date boundary." }
        val result = StockSyncResult(date, (counts["skus"] as Number).toInt(), (counts["lots"] as Number).toInt(), now)
        syncStatus.recordSuccess(result)
        jdbc.update(
            "UPDATE stock_ledger_run SET status = 'COMPLETED', completed_at = ?, last_error = NULL WHERE id = ?",
            Timestamp.from(now), runId
        )
        release(owner)
        return result
    }

    @Transactional
    override fun fail(runId: UUID, owner: UUID, error: String, now: Instant) {
        val control = jdbc.queryForMap("SELECT * FROM stock_ledger_coordinator WHERE id = 1 FOR UPDATE")
        if (control["owner"] != owner || control["run_id"] != runId) return
        jdbc.update(
            "UPDATE stock_ledger_run SET status = 'PARTIAL', last_error = ? WHERE id = ?",
            error.take(1000), runId
        )
        release(owner)
    }

    override fun getProgress(): StockLedgerProgress? = jdbc.query(
        """
        SELECT r.*, c.lease_until, COUNT(w.sku) AS total_skus,
               COUNT(w.completed_at) AS completed_skus
        FROM stock_ledger_coordinator c JOIN stock_ledger_run r ON r.id = c.run_id
        LEFT JOIN stock_ledger_work w ON w.run_id = r.id
        WHERE c.id = 1 GROUP BY r.id, c.lease_until
        """.trimIndent(),
        { rs, _ ->
            StockLedgerProgress(
                rs.getObject("id", UUID::class.java), rs.getObject("snapshot_date", LocalDate::class.java),
                rs.getString("status"), rs.getInt("total_skus"), rs.getInt("completed_skus"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("completed_at")?.toInstant(),
                rs.getTimestamp("lease_until")?.toInstant(), rs.getString("last_error")
            )
        }
    ).singleOrNull()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun recordReceipt(followup: StockReceiptFollowup, runId: UUID, now: Instant) {
        jdbc.update(
            "INSERT INTO stock_receipt_followup (id, run_id, sku, as_of_date, created_at) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
            followup.id, runId, followup.sku, followup.asOfDate, Timestamp.from(now)
        )
    }

    override fun pendingReceipts(limit: Int): List<StockReceiptFollowup> = jdbc.query(
        "SELECT id, sku, as_of_date FROM stock_receipt_followup " +
            "WHERE dispatched_at IS NULL ORDER BY created_at, id LIMIT ?",
        { rs, _ ->
            StockReceiptFollowup(
                rs.getObject("id", UUID::class.java), rs.getString("sku"),
                rs.getObject("as_of_date", LocalDate::class.java)
            )
        }, limit
    )

    @Transactional
    override fun receiptDispatched(id: UUID, now: Instant) {
        jdbc.update(
            "UPDATE stock_receipt_followup SET dispatched_at = ?, last_error = NULL WHERE id = ?",
            Timestamp.from(now), id
        )
    }

    @Transactional
    override fun receiptFailed(id: UUID, error: String) {
        jdbc.update("UPDATE stock_receipt_followup SET last_error = ? WHERE id = ?", error.take(1000), id)
    }

    private fun checkOwner(runId: UUID, owner: UUID) {
        val control = jdbc.queryForMap("SELECT * FROM stock_ledger_coordinator WHERE id = 1 FOR UPDATE")
        check(control["owner"] == owner && control["run_id"] == runId) { "Ledger coordinator ownership was lost." }
    }

    private fun release(owner: UUID) {
        jdbc.update("UPDATE stock_ledger_coordinator SET owner = NULL, lease_until = NULL WHERE id = 1 AND owner = ?", owner)
    }

    companion object {
        private val LEASE = Duration.ofMinutes(5)
    }
}

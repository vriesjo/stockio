package nl.stockio.allocation.infra

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import nl.stockio.allocation.domain.api.data.StockSyncResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class LedgerSyncState(
    val monitoringStartedAt: Instant,
    val lastSuccessAt: Instant?,
    val snapshotDate: LocalDate?,
    val skuCount: Int?,
    val lotSnapshotCount: Int?
)

@Repository
class LedgerSyncStatusRepository(private val jdbcTemplate: JdbcTemplate) {
    fun get(): LedgerSyncState = checkNotNull(jdbcTemplate.queryForObject(
        "SELECT * FROM stock_ledger_sync_status WHERE id = 1",
        { resultSet, _ ->
            LedgerSyncState(
                monitoringStartedAt = resultSet.getTimestamp("monitoring_started_at").toInstant(),
                lastSuccessAt = resultSet.getTimestamp("last_success_at")?.toInstant(),
                snapshotDate = resultSet.getObject("snapshot_date", LocalDate::class.java),
                skuCount = resultSet.getObject("sku_count", Int::class.javaObjectType),
                lotSnapshotCount = resultSet.getObject("lot_snapshot_count", Int::class.javaObjectType)
            )
        }
    )) { "The ledger synchronization status row is missing." }

    @Transactional(propagation = Propagation.MANDATORY)
    fun recordSuccess(result: StockSyncResult) {
        check(jdbcTemplate.update(
            """
                UPDATE stock_ledger_sync_status
                SET last_success_at = ?, snapshot_date = ?, sku_count = ?, lot_snapshot_count = ?
                WHERE id = 1
            """.trimIndent(),
            Timestamp.from(result.updatedAt), result.snapshotDate, result.skuCount, result.lotCount
        ) == 1) { "The ledger synchronization status row is missing." }
    }
}

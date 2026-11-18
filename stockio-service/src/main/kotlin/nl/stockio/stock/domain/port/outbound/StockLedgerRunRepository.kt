package nl.stockio.stock.domain.port

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.StockSyncResult

data class StockLedgerRun(val id: UUID, val snapshotDate: LocalDate, val skus: List<String>)

data class StockLedgerProgress(
    val runId: UUID,
    val snapshotDate: LocalDate,
    val status: String,
    val totalSkus: Int,
    val completedSkus: Int,
    val createdAt: Instant,
    val completedAt: Instant?,
    val leaseUntil: Instant?,
    val lastError: String?
)

data class StockReceiptFollowup(val id: UUID, val sku: String, val asOfDate: LocalDate)

interface StockLedgerRunRepository {
    fun begin(snapshotDate: LocalDate, owner: UUID, now: Instant): StockLedgerRun
    fun lockWork(runId: UUID, sku: String, owner: UUID): Boolean
    fun completeWork(runId: UUID, sku: String, owner: UUID, skuCount: Int, lotCount: Int, now: Instant)
    fun complete(runId: UUID, owner: UUID, now: Instant): StockSyncResult
    fun fail(runId: UUID, owner: UUID, error: String, now: Instant)
    fun getProgress(): StockLedgerProgress?
    fun recordReceipt(followup: StockReceiptFollowup, runId: UUID, now: Instant)
    fun pendingReceipts(limit: Int): List<StockReceiptFollowup>
    fun receiptDispatched(id: UUID, now: Instant)
    fun receiptFailed(id: UUID, error: String)
}

class StockLedgerBusyException : RuntimeException("A stock-ledger coordinator is already running.")

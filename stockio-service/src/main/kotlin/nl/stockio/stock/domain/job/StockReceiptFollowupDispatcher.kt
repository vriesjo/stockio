package nl.stockio.stock.domain.job

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.util.UUID
import nl.stockio.allocation.domain.job.BackorderAllocationJob
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.scheduling.JobScheduler
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class StockReceiptFollowupDispatcher(
    private val repository: StockLedgerRunRepository,
    private val scheduler: JobScheduler,
    private val backorderJob: BackorderAllocationJob,
    private val clock: Clock
) {
    @EventListener(ApplicationReadyEvent::class)
    fun register() {
        scheduler.scheduleRecurrently("dispatch-stock-receipt-followups", "* * * * *") { dispatch() }
    }

    @Job(name = "Dispatch durable stock receipt follow-up", retries = 3)
    fun dispatch() {
        repository.pendingReceipts(100).forEach { receipt ->
            try {
                val command = BackorderAllocationRequest(
                    requestId = receipt.id,
                    asOfDate = receipt.asOfDate,
                    trigger = BackorderAllocationTrigger.STOCK_LEDGER_RECEIPT,
                    skuScope = setOf(receipt.sku)
                )
                val jobId = UUID.nameUUIDFromBytes("stock-ledger-backorder-job:${receipt.id}".toByteArray())
                scheduler.enqueue(jobId) { backorderJob.execute(command) }
                repository.receiptDispatched(receipt.id, clock.instant())
            } catch (exception: Exception) {
                repository.receiptFailed(receipt.id, exception.message ?: exception.javaClass.simpleName)
                logger.warn(exception) { "Receipt follow-up '${receipt.id}' remains pending." }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

package nl.stockio.stock.domain.job

import nl.stockio.stock.domain.processor.StockLedgerProcessor
import nl.stockio.stock.domain.service.StockLedgerSyncProperties
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.scheduling.JobScheduler
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Schedules ledger synchronization independently of allocation.
 * The stable recurring ID is shared across pods; database locking makes retries safe.
 */
@Component
class StockLedgerSyncJob(
    private val processor: StockLedgerProcessor,
    private val properties: StockLedgerSyncProperties,
    private val scheduler: JobScheduler
) {
    @EventListener(ApplicationReadyEvent::class)
    fun register() {
        if (properties.enabled) {
            scheduler.scheduleRecurrently(RECURRING_ID, properties.cron, properties.zoneId) { execute() }
        } else {
            scheduler.deleteRecurringJob(RECURRING_ID)
        }
    }

    @Job(name = "Synchronize live stock ledger", retries = 3)
    fun execute() {
        processor.processToday()
    }

    companion object {
        const val RECURRING_ID = "sync-stock-ledger"
    }
}

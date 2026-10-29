package nl.stockio.stock.domain.job

import nl.stockio.stock.domain.processor.StockLedgerProcessor
import nl.stockio.stock.domain.service.StockLedgerSyncProperties
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class StockLedgerSyncJobTest {
    @Test
    fun `registers a stable recurring job in the configured zone`() {
        val scheduler = Mockito.mock(JobScheduler::class.java)
        val processor = Mockito.mock(StockLedgerProcessor::class.java)
        val properties = StockLedgerSyncProperties(zone = "Europe/Amsterdam")
        StockLedgerSyncJob(processor, properties, scheduler).register()

        val lambda = ArgumentCaptor.forClass(JobLambda::class.java)
        Mockito.verify(scheduler).scheduleRecurrently(
            ArgumentMatchers.eq(StockLedgerSyncJob.RECURRING_ID),
            ArgumentMatchers.eq(properties.cron),
            ArgumentMatchers.eq(properties.zoneId), lambda.capture()
        )
        lambda.value.run()
        Mockito.verify(processor).processToday()
    }

    @Test
    fun `disabled scheduling removes an existing recurring definition`() {
        val scheduler = Mockito.mock(JobScheduler::class.java)
        val processor = Mockito.mock(StockLedgerProcessor::class.java)
        StockLedgerSyncJob(processor, StockLedgerSyncProperties(enabled = false), scheduler).register()
        Mockito.verify(scheduler).deleteRecurringJob(StockLedgerSyncJob.RECURRING_ID)
        Mockito.verifyNoInteractions(processor)
    }

    @Test
    fun `execution propagates synchronization failures for JobRunr retries`() {
        val processor = Mockito.mock(StockLedgerProcessor::class.java)
        Mockito.doThrow(IllegalStateException("Snapshot rebuild failed")).`when`(processor).processToday()
        val job = StockLedgerSyncJob(processor, StockLedgerSyncProperties(), Mockito.mock(JobScheduler::class.java))
        Assertions.assertThrows(IllegalStateException::class.java) { job.execute() }
    }
}

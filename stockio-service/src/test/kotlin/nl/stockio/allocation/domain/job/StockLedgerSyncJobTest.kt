package nl.stockio.allocation.domain.job

import nl.stockio.stock.domain.job.StockLedgerSyncJob
import nl.stockio.stock.domain.processor.StockLedgerProcessor
import nl.stockio.stock.domain.service.StockLedgerSyncProperties
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class StockLedgerSyncJobTest {
    @Test
    fun `registers a stable recurring job in the configured zone`() {
        val scheduler = mock(JobScheduler::class.java)
        val processor = mock(StockLedgerProcessor::class.java)
        val properties = StockLedgerSyncProperties(zone = "Europe/Amsterdam")
        StockLedgerSyncJob(processor, properties, scheduler).register()

        val lambda = ArgumentCaptor.forClass(JobLambda::class.java)
        verify(scheduler).scheduleRecurrently(
            eq(StockLedgerSyncJob.RECURRING_ID), eq(properties.cron), eq(properties.zoneId), lambda.capture()
        )
        lambda.value.run()
        verify(processor).processToday()
    }

    @Test
    fun `disabled scheduling removes an existing recurring definition`() {
        val scheduler = mock(JobScheduler::class.java)
        val processor = mock(StockLedgerProcessor::class.java)
        StockLedgerSyncJob(processor, StockLedgerSyncProperties(enabled = false), scheduler).register()
        verify(scheduler).deleteRecurringJob(StockLedgerSyncJob.RECURRING_ID)
        verifyNoInteractions(processor)
    }

    @Test
    fun `execution propagates synchronization failures for JobRunr retries`() {
        val processor = mock(StockLedgerProcessor::class.java)
        doThrow(IllegalStateException("Snapshot rebuild failed")).`when`(processor).processToday()
        val job = StockLedgerSyncJob(processor, StockLedgerSyncProperties(), mock(JobScheduler::class.java))
        assertThrows(IllegalStateException::class.java) { job.execute() }
    }
}

package nl.stockio.stock.domain.job

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.allocation.domain.job.BackorderAllocationJob
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import nl.stockio.stock.domain.port.StockReceiptFollowup
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Test

class StockReceiptFollowupDispatcherTest {
    private val repository = mockk<StockLedgerRunRepository>(relaxed = true)
    private val scheduler = mockk<JobScheduler>()
    private val job = mockk<BackorderAllocationJob>(relaxed = true)
    private val now = Instant.parse("2026-10-26T10:00:00Z")
    private val receipt = StockReceiptFollowup(UUID.randomUUID(), "SKU", LocalDate.of(2026, 10, 26))
    private val dispatcher = StockBackorderFollowupJob(
        repository, scheduler, job, Clock.fixed(now, ZoneOffset.UTC)
    )

    @Test
    fun `enqueue outage leaves durable receipt pending for the next dispatch`() {
        every { repository.pendingReceipts(100) } returns listOf(receipt)
        every { scheduler.enqueue(any<UUID>(), any<JobLambda>()) } throws IllegalStateException("Unavailable")

        dispatcher.execute()

        verify { repository.receiptFailed(receipt.id, "Unavailable") }
        verify(exactly = 0) { repository.receiptDispatched(any(), any()) }
    }

    @Test
    fun `repeated dispatch uses stable job and consumption identities`() {
        every { repository.pendingReceipts(100) } returns listOf(receipt)
        val lambda = slot<JobLambda>()
        every { scheduler.enqueue(any<UUID>(), capture(lambda)) } returns mockk()
        val command = slot<BackorderAllocationRequest>()
        every { job.execute(capture(command)) } returns Unit

        repeat(2) {
            dispatcher.execute()
            lambda.captured.run()
            command.captured.requestId shouldBe receipt.id
            command.captured.skuScope shouldBe setOf("SKU")
            command.captured.asOfDate shouldBe receipt.asOfDate
        }
        val id = UUID.nameUUIDFromBytes("stock-ledger-backorder-job:${receipt.id}".toByteArray())
        verify(exactly = 2) { scheduler.enqueue(id, any<JobLambda>()) }
        verify(exactly = 2) { repository.receiptDispatched(receipt.id, now) }
    }
}

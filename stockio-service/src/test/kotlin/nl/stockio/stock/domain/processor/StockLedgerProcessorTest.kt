package nl.stockio.stock.domain.processor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import nl.stockio.api.dto.ProcessDailyStockRequest
import nl.stockio.api.dto.StockSyncResult
import nl.stockio.reservation.domain.service.ReservationCleanupService
import nl.stockio.stock.domain.job.StockReceiptFollowupDispatcher
import nl.stockio.stock.domain.port.StockLedgerRun
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import org.junit.jupiter.api.Test

class StockLedgerProcessorTest {
    private val now = Instant.parse("2026-10-26T10:00:00Z")
    private val today = LocalDate.of(2026, 10, 26)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = mockk<StockLedgerRunRepository>(relaxed = true)
    private val worker = mockk<StockLedgerSkuWorker>(relaxed = true)
    private val cleanup = mockk<ReservationCleanupService>(relaxed = true)
    private val followups = mockk<StockReceiptFollowupDispatcher>(relaxed = true)
    private val processor = StockLedgerProcessor(repository, worker, cleanup, followups, clock)
    private val run = StockLedgerRun(UUID.randomUUID(), today, listOf("A", "B"))
    private val result = StockSyncResult(today, 2, 3, now)

    @Test
    fun `returns success and dispatches only after the durable manifest completes`() {
        every { repository.begin(today, any(), now) } returns run
        every { repository.complete(run.id, any(), now) } returns result

        processor.processToday() shouldBe result

        verifyOrder {
            worker.process(run, any(), "A")
            worker.process(run, any(), "B")
            repository.complete(run.id, any(), now)
            followups.dispatch()
        }
    }

    @Test
    fun `partial failure remains resumable without recording global success`() {
        every { repository.begin(today, any(), now) } returns run
        every { worker.process(run, any(), "B") } throws IllegalStateException("SKU failure")

        shouldThrow<IllegalStateException> { processor.processToday() }.message shouldBe "SKU failure"

        verify { repository.fail(run.id, any(), "SKU failure", now) }
        verify(exactly = 0) { repository.complete(any(), any(), any()) }
        verify(exactly = 0) { followups.dispatch() }
    }

    @Test
    fun `whole-order cleanup occurs outside the SKU worker transaction before retry`() {
        every { repository.begin(today, any(), now) } returns run.copy(skus = listOf("A"))
        every { repository.complete(run.id, any(), now) } returns result
        every { worker.process(any(), any(), "A") } throws
            LedgerCleanupRequiredException(listOf("ORDER")) andThen Unit

        processor.processToday() shouldBe result

        verifyOrder {
            worker.process(any(), any(), "A")
            cleanup.expireOrders(listOf("ORDER"))
            worker.process(any(), any(), "A")
            repository.complete(run.id, any(), now)
        }
    }

    @Test
    fun `does not start a past or future date manifest`() {
        listOf(today.minusDays(1), today.plusDays(1)).forEach { date ->
            shouldThrow<IllegalArgumentException> { processor.process(ProcessDailyStockRequest(date)) }
        }
        verify(exactly = 0) { repository.begin(any(), any(), any()) }
    }

    @Test
    fun `does not advance global freshness when a manifest crosses midnight`() {
        val changingClock = mockk<Clock>()
        every { changingClock.zone } returns ZoneOffset.UTC
        every { changingClock.instant() } returns now
        every { repository.begin(today, any(), now) } returns run.copy(skus = emptyList())
        every { repository.begin(today, any(), now) } answers {
            every { changingClock.instant() } returns now.plusSeconds(86400)
            run.copy(skus = emptyList())
        }
        val changingProcessor = StockLedgerProcessor(repository, worker, cleanup, followups, changingClock)

        shouldThrow<IllegalArgumentException> { changingProcessor.processToday() }
        verify(exactly = 0) { repository.complete(any(), any(), any()) }
        verify { repository.fail(run.id, any(), any(), now.plusSeconds(86400)) }
    }
}

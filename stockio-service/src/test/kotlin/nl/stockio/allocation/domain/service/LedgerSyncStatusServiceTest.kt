package nl.stockio.allocation.domain.service

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import nl.stockio.allocation.infra.LedgerSyncState
import nl.stockio.allocation.infra.LedgerSyncStatusRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.actuate.health.Status

class LedgerSyncStatusServiceTest {
    private val repository = mock(LedgerSyncStatusRepository::class.java)
    private val baseline = Instant.parse("2026-10-20T00:05:00Z")

    @Test
    fun `missed cron occurrence becomes unhealthy only after the tolerance window`() {
        `when`(repository.get()).thenReturn(
            LedgerSyncState(baseline, baseline, LocalDate.parse("2026-10-20"), 2, 3)
        )
        val onBoundary = service("2026-10-20T13:00:00Z")
        assertFalse(onBoundary.getStatus().stale)
        val overdue = service("2026-10-20T13:00:01Z")
        assertEquals(Instant.parse("2026-10-20T12:00:00Z"), overdue.getStatus().nextExpectedAt)
        assertTrue(overdue.getStatus().stale)
        assertEquals(baseline, overdue.getStatus().lastSuccessAt)
        assertEquals(Status.DOWN, StockLedgerHealthIndicator(overdue).health().status)
    }

    @Test
    fun `never synchronized ledger has an initial grace period without inventing a success`() {
        `when`(repository.get()).thenReturn(LedgerSyncState(baseline, null, null, null, null))
        val status = service("2026-10-20T12:30:00Z").getStatus()
        assertFalse(status.stale)
        assertNull(status.lastSuccessAt)
        assertTrue(service("2026-10-20T13:00:01Z").getStatus().stale)
    }

    @Test
    fun `disabled automatic synchronization does not trigger a schedule freshness alarm`() {
        `when`(repository.get()).thenReturn(LedgerSyncState(baseline, null, null, null, null))
        val service = service("2026-10-22T18:00:00Z", enabled = false)
        assertFalse(service.getStatus().stale)
        assertEquals(Status.UNKNOWN, StockLedgerHealthIndicator(service).health().status)
    }

    private fun service(now: String, enabled: Boolean = true) = LedgerSyncStatusService(
        repository, LedgerSyncProperties(enabled = enabled), Clock.fixed(Instant.parse(now), ZoneOffset.UTC)
    )
}

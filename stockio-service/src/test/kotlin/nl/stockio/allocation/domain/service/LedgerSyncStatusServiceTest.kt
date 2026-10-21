package nl.stockio.allocation.domain.service

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import nl.stockio.stock.domain.service.StockLedgerSyncProperties
import nl.stockio.stock.domain.service.StockLedgerService
import nl.stockio.stock.domain.service.StockLedgerHealthIndicator
import nl.stockio.stock.infra.StockLedgerSyncState
import nl.stockio.stock.infra.StockLedgerSyncStatusJdbcTemplateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.actuate.health.Status

class LedgerSyncStatusServiceTest {
    private val repository = mock(StockLedgerSyncStatusJdbcTemplateRepository::class.java)
    private val baseline = Instant.parse("2026-10-20T00:05:00Z")

    @Test
    fun `missed cron occurrence becomes unhealthy only after the tolerance window`() {
        `when`(repository.getSyncStatus()).thenReturn(
            StockLedgerSyncState(baseline, baseline, LocalDate.parse("2026-10-20"), 2, 3)
        )
        val onBoundary = service("2026-10-20T13:00:00Z")
        assertFalse(onBoundary.getStatusLastSyncRun().stale)
        val overdue = service("2026-10-20T13:00:01Z")
        assertEquals(Instant.parse("2026-10-20T12:00:00Z"), overdue.getStatusLastSyncRun().nextExpectedAt)
        assertTrue(overdue.getStatusLastSyncRun().stale)
        assertEquals(baseline, overdue.getStatusLastSyncRun().lastSuccessAt)
        assertEquals(Status.DOWN, StockLedgerHealthIndicator(overdue).health().status)
    }

    @Test
    fun `never synchronized ledger has an initial grace period without inventing a success`() {
        `when`(repository.getSyncStatus()).thenReturn(StockLedgerSyncState(baseline, null, null, null, null))
        val status = service("2026-10-20T12:30:00Z").getStatusLastSyncRun()
        assertFalse(status.stale)
        assertNull(status.lastSuccessAt)
        assertTrue(service("2026-10-20T13:00:01Z").getStatusLastSyncRun().stale)
    }

    @Test
    fun `disabled automatic synchronization does not trigger a schedule freshness alarm`() {
        `when`(repository.getSyncStatus()).thenReturn(StockLedgerSyncState(baseline, null, null, null, null))
        val service = service("2026-10-22T18:00:00Z", enabled = false)
        assertFalse(service.getStatusLastSyncRun().stale)
        assertEquals(Status.UNKNOWN, StockLedgerHealthIndicator(service).health().status)
    }

    private fun service(now: String, enabled: Boolean = true) = StockLedgerService(
        repository, StockLedgerSyncProperties(enabled = enabled), Clock.fixed(Instant.parse(now), ZoneOffset.UTC)
    )
}

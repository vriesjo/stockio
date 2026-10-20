package nl.stockio.stock.domain.service

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import nl.stockio.stock.infra.StockLedgerSyncStatusJdbcTemplateRepository
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

data class LedgerSyncStatus(
    val schedulingEnabled: Boolean,
    val lastSuccessAt: Instant?,
    val snapshotDate: LocalDate?,
    val skuCount: Int?,
    val lotSnapshotCount: Int?,
    val nextExpectedAt: Instant,
    val staleAfter: Instant,
    val stale: Boolean
)

@Service
class LedgerSyncStatusService(
    private val repository: StockLedgerSyncStatusJdbcTemplateRepository,
    private val properties: StockLedgerSyncProperties,
    private val clock: Clock
) {
    fun getStatus(): LedgerSyncStatus {
        val state = repository.get()
        val baseline = state.lastSuccessAt ?: state.monitoringStartedAt
        val nextExpected = checkNotNull(properties.schedule.next(baseline, baseline, properties.zoneId)) {
            "Ledger synchronization schedule has no next occurrence."
        }
        val staleAfter = nextExpected.plus(properties.freshnessTolerance)
        return LedgerSyncStatus(
            schedulingEnabled = properties.enabled,
            lastSuccessAt = state.lastSuccessAt,
            snapshotDate = state.snapshotDate,
            skuCount = state.skuCount,
            lotSnapshotCount = state.lotSnapshotCount,
            nextExpectedAt = nextExpected,
            staleAfter = staleAfter,
            stale = properties.enabled && clock.instant() > staleAfter
        )
    }
}

@Component("stockLedgerHealthIndicator")
class StockLedgerHealthIndicator(private val statusService: LedgerSyncStatusService) : HealthIndicator {
    override fun health(): Health {
        val status = statusService.getStatus()
        val builder = when {
            !status.schedulingEnabled -> Health.unknown()
            status.stale -> Health.down()
            else -> Health.up()
        }
        return builder.withDetail("ledgerSync", status).build()
    }
}

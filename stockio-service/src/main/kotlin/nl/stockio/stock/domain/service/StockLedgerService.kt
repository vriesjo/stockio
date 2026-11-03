package nl.stockio.stock.domain.service

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import nl.stockio.stock.infra.StockLedgerSyncStatusJdbcTemplateRepository
import nl.stockio.stock.domain.port.StockLedgerProgress
import nl.stockio.stock.domain.port.StockLedgerRunRepository
import org.jobrunr.scheduling.cron.CronExpression
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

data class StockLedgerSyncStatus(
    val schedulingEnabled: Boolean,
    val lastSuccessAt: Instant?,
    val snapshotDate: LocalDate?,
    val skuCount: Int?,
    val lotSnapshotCount: Int?,
    val nextExpectedAt: Instant,
    val staleAfter: Instant,
    val stale: Boolean,
    val progress: StockLedgerProgress? = null
)

@Service
class StockLedgerService(
    private val repository: StockLedgerSyncStatusJdbcTemplateRepository,
    private val properties: StockLedgerSyncProperties,
    private val clock: Clock,
    private val runRepository: StockLedgerRunRepository
) {
    fun getStatusLastSyncRun(): StockLedgerSyncStatus {
        val syncState = repository.getSyncStatus()
        val baseline = syncState.lastSuccessAt ?: syncState.monitoringStartedAt
        val nextExpected = checkNotNull(properties.schedule.next(baseline, baseline, properties.zoneId)) {
            "Ledger synchronization schedule has no next occurrence."
        }
        val staleAfter = nextExpected.plus(properties.freshnessTolerance)
        return StockLedgerSyncStatus(
            schedulingEnabled = properties.enabled,
            lastSuccessAt = syncState.lastSuccessAt,
            snapshotDate = syncState.snapshotDate,
            skuCount = syncState.skuCount,
            lotSnapshotCount = syncState.lotSnapshotCount,
            nextExpectedAt = nextExpected,
            staleAfter = staleAfter,
            stale = properties.enabled && clock.instant() > staleAfter,
            progress = runRepository.getProgress()
        )
    }
}

@Component("stockLedgerHealthIndicator")
class StockLedgerHealthIndicator(private val statusService: StockLedgerService) : HealthIndicator {
    override fun health(): Health {
        val status = statusService.getStatusLastSyncRun()
        val builder = when {
            !status.schedulingEnabled -> Health.unknown()
            status.stale -> Health.down()
            else -> Health.up()
        }
        return builder.withDetail("ledgerSync", status).build()
    }
}

@ConfigurationProperties("stockio.stock.ledger-sync")
data class StockLedgerSyncProperties(
    val enabled: Boolean = true,
    val cron: String = "0 0,12 * * *",
    val zone: String = "UTC",
    val freshnessTolerance: Duration = Duration.ofHours(1)
) {
    val zoneId: ZoneId = ZoneId.of(zone)
    val schedule: CronExpression = CronExpression(cron)

    init {
        require(cron.trim().split(Regex("\\s+")).size == 5) { "Ledger synchronization cron must have five fields." }
        require(!freshnessTolerance.isNegative) { "Ledger synchronization freshness tolerance must not be negative." }
    }
}

@Configuration
@EnableConfigurationProperties(StockLedgerSyncProperties::class)
class StockLedgerSyncConfiguration {
    @Bean
    fun systemClock(properties: StockLedgerSyncProperties): Clock = Clock.system(properties.zoneId)
}

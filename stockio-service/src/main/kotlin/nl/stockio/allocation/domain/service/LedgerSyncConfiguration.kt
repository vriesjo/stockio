package nl.stockio.allocation.domain.service

import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import org.jobrunr.scheduling.cron.CronExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("stockio.allocation.ledger-sync")
data class LedgerSyncProperties(
    val enabled: Boolean = true,
    val cron: String = "0 0,12 * * *",
    val zone: String = "UTC",
    val freshnessTolerance: Duration = Duration.ofHours(1)
) {
    val zoneId: ZoneId = ZoneId.of(zone)
    val schedule: CronExpression = CronExpression(cron)

    init {
        require(cron.trim().split(Regex("\\s+")).size == 5) {
            "Ledger synchronization cron must have five fields."
        }
        require(!freshnessTolerance.isNegative) { "Ledger synchronization freshness tolerance must not be negative." }
    }
}

@Configuration
@EnableConfigurationProperties(LedgerSyncProperties::class)
class LedgerSyncConfiguration {
    @Bean
    fun businessClock(properties: LedgerSyncProperties): Clock = Clock.system(properties.zoneId)
}

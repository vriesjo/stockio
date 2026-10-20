package nl.stockio.allocation.domain.service

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LedgerSyncConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = [
        "cron=invalid", "cron=0 0 30 2 *", "cron=0 0 0 * * *",
        "zone=Invalid/Zone", "freshness-tolerance=-1m"
    ])
    fun `invalid ledger settings fail application startup`(setting: String) {
        ApplicationContextRunner()
            .withUserConfiguration(LedgerSyncConfiguration::class.java)
            .withPropertyValues("stockio.allocation.ledger-sync.$setting")
            .run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun `business clock and twice daily schedule use the same configured zone`() {
        ApplicationContextRunner()
            .withUserConfiguration(LedgerSyncConfiguration::class.java)
            .withPropertyValues("stockio.allocation.ledger-sync.zone=Europe/Amsterdam")
            .run { context ->
                assertNull(context.startupFailure)
                val properties = context.getBean(LedgerSyncProperties::class.java)
                assertEquals(ZoneId.of("Europe/Amsterdam"), context.getBean(Clock::class.java).zone)
                val beforeNoon = Instant.parse("2026-10-20T08:00:00Z")
                assertEquals(
                    Instant.parse("2026-10-20T10:00:00Z"),
                    properties.schedule.next(beforeNoon, beforeNoon, properties.zoneId)
                )
            }
    }
}

package nl.stockio.allocation.domain.service

import nl.stockio.reservation.domain.service.ReservationConfiguration
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ReservationConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["ttl=0m", "ttl=-1m", "expiry-interval=0s", "expiry-batch-size=0"])
    fun `invalid reservation settings fail application startup`(setting: String) {
        ApplicationContextRunner()
            .withUserConfiguration(ReservationConfiguration::class.java)
            .withPropertyValues("stockio.allocation.reservation.$setting")
            .run { context -> assertNotNull(context.startupFailure) }
    }
}

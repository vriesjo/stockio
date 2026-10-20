package nl.stockio.reservation.domain.service

import java.time.Clock
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("stockio.allocation.reservation")
data class ReservationProperties(
    val ttl: Duration = Duration.ofMinutes(30),
    val expiryInterval: Duration = Duration.ofMinutes(1),
    val expiryBatchSize: Int = 100
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "Reservation TTL must be positive." }
        require(!expiryInterval.isNegative && !expiryInterval.isZero) {
            "Reservation expiry interval must be positive."
        }
        require(expiryBatchSize > 0) { "Reservation expiry batch size must be positive." }
    }
}

@Configuration
@EnableConfigurationProperties(ReservationProperties::class)
class ReservationConfiguration {
    @Bean
    fun reservationClock(): Clock = Clock.systemDefaultZone()
}

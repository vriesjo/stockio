package nl.stockio.allocation.infra

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "stockio.allocation.redis")
data class AllocationGatewayProperties(
    val enabled: Boolean = false,
    val holdTtl: Duration = Duration.ofMinutes(10),
    val defaultWarehouse: String = "MAIN",
    val redisHost: String = "localhost",
    val redisPort: Int = 6379,
    val cdc: Cdc = Cdc()
) {
    data class Cdc(
        val enabled: Boolean = false,
        val topic: String = "stockio.cdc.public.stock",
        val groupId: String = "stockio-stock-cdc"
    )
}

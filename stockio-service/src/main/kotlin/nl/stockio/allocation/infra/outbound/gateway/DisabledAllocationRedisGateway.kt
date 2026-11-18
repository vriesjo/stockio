package nl.stockio.allocation.infra

import nl.stockio.allocation.domain.port.outbound.AllocationGateway
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.RedisKeyResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(AllocationGatewayProperties::class)
class AllocationGatewayDisabledConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "stockio.allocation.redis", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledAllocationGateway(): AllocationGateway = DisabledAllocationRedisGateway()
}

class DisabledAllocationRedisGateway : AllocationGateway {
    override val enabled: Boolean = false

    override fun accept(request: AllocationRequest) = Unit

    override fun rollback(request: AllocationRequest) = Unit

    override fun settle(request: AllocationRequest) = Unit

    override fun getByKey(key: String): RedisKeyResponse? = null
}

class AllocationRedisSyncDisabledException :
    IllegalStateException("Redis accepts only async allocation jobs.")

class AllocationRedisNotReadyException :
    IllegalStateException("Redis is rebuilding availability.")

class AllocationRedisInsufficientStockException :
    IllegalStateException("Redis availability cannot cover this allocation request.")

class AllocationRedisHoldConflictException(message: String) : IllegalStateException(message)

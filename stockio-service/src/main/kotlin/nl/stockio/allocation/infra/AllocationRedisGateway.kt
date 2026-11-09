package nl.stockio.allocation.infra

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import nl.stockio.allocation.domain.fastpath.AllocationGatewayRedisHoldLine
import nl.stockio.allocation.domain.fastpath.RedisKeys
import nl.stockio.allocation.domain.port.AllocationGateway
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.RedisKeyResponse
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.listener.PatternTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Configuration
@EnableConfigurationProperties(AllocationGatewayProperties::class)
@ConditionalOnProperty(prefix = "stockio.allocation.redis", name = ["enabled"], havingValue = "true")
class AllocationRedisGatewayEnabledConfiguration {
    @Bean
    fun redisConnectionFactory(properties: AllocationGatewayProperties): LettuceConnectionFactory =
        LettuceConnectionFactory(properties.redisHost, properties.redisPort)

    @Bean
    fun stringRedisTemplate(connectionFactory: RedisConnectionFactory): StringRedisTemplate =
        StringRedisTemplate(connectionFactory)

    // The RedisMessageListenerContainer is used to listen for key expiration events in Redis.
    // When a reservation expires, the onReservationExpired method of the AllocationRedisGateway
    // is called to handle the expiration.
    @Bean
    fun expirationContainer(
        connectionFactory: RedisConnectionFactory,
        gateway: AllocationRedisGateway
    ): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
            addMessageListener({ message, _ ->
                gateway.onReservationExpired(String(message.body))
            }, PatternTopic("__keyevent@0__:expired"))
        }
}

@Service
@ConditionalOnProperty(prefix = "stockio.allocation.redis", name = ["enabled"], havingValue = "true")
class AllocationRedisGateway(
    private val redis: StringRedisTemplate,
    private val properties: AllocationGatewayProperties,
    private val availability: StockAvailabilityJpaRepository,
    private val objectMapper: ObjectMapper
) : AllocationGateway, ApplicationRunner {
    private val accepting = AtomicBoolean(false)
    private val holdScript = script("redis/fast-path-hold.lua")
    private val rollbackScript = script("redis/fast-path-rollback.lua")

    override val enabled: Boolean = true

    override fun run(args: ApplicationArguments) {
        rebuild()
    }

    fun rebuild() {
        accepting.set(false)
        enableKeyspaceEvents()
        availability.sumBySkuAndWarehouse().forEach { row ->
            redis.opsForValue().set(
                RedisKeys.stock(row.sku, row.warehouseCode),
                row.quantityAvailable.toString()
            )
        }
        accepting.set(true)
        logger.info { "Fast path Redis availability rebuilt from PostgreSQL." }
    }

    override fun accept(request: AllocationRequest) {
        if (!accepting.get()) throw AllocationRedisNotReadyException()
        val lines = RedisKeys.lines(request, properties.defaultWarehouse)
        if (lines.isEmpty()) return
        val result = try {
            redis.execute(
                holdScript,
                keysFor(lines),
                properties.holdTtl.seconds.toString(),
                Instant.now().toString(),
                objectMapper.writeValueAsString(lines.map { it.toScriptLine() })
            )
        } catch (exception: RuntimeException) {
            throw AllocationRedisHoldConflictException(exception.message ?: "Redis hold failed.")
        }
        when (result) {
            0L -> throw AllocationRedisInsufficientStockException()
            1L, 2L -> Unit
            else -> throw AllocationRedisHoldConflictException("Unexpected Redis hold result '$result'.")
        }
    }

    override fun rollback(request: AllocationRequest) {
        val lines = RedisKeys.lines(request, properties.defaultWarehouse)
        if (lines.isEmpty()) return
        redis.execute(rollbackScript, keysFor(lines), "rollback")
    }

    // The settle method is called when an allocation request is successfully processed and the reserved stock should be released.
    // It deletes the reservation and expiry keys from Redis, effectively releasing the reserved stock back to the available stock.
    // This method is called after the allocation processing to ensure that we only settle if the allocation was successful.
    override fun settle(request: AllocationRequest) {
        val lines = RedisKeys.lines(request, properties.defaultWarehouse)
        if (lines.isEmpty()) return
        val keys = lines.flatMap { listOf(RedisKeys.reservation(it), RedisKeys.expiry(it)) }
        redis.delete(keys)
    }

    override fun getByKey(key: String): RedisKeyResponse? {
        val ttl = redis.getExpire(key)?.takeIf { it >= 0 }
        return when (redis.type(key)) {
            DataType.STRING -> RedisKeyResponse(key, value = redis.opsForValue().get(key), ttlSeconds = ttl)
            DataType.HASH -> RedisKeyResponse(
                key,
                fields = redis.opsForHash<String, String>().entries(key),
                ttlSeconds = ttl
            )
            else -> null
        }
    }

    // This method is called when a reservation key expires in Redis. It retrieves the corresponding
    // reservation line and increments the stock quantity back to the stock key. It also deletes the
    // reservation key to clean up.
    fun onReservationExpired(expiredKey: String) {
        val line = expiredLine(expiredKey) ?: return
        val reservationKey = RedisKeys.reservation(line)
        val qty = redis.opsForHash<String, String>().get(reservationKey, "qty")?.toLongOrNull() ?: return
        val sku = redis.opsForHash<String, String>().get(reservationKey, "sku") ?: line.sku
        val warehouse = redis.opsForHash<String, String>().get(reservationKey, "warehouse") ?: line.warehouse
        redis.opsForValue().increment(RedisKeys.stock(sku, warehouse), qty)
        redis.delete(reservationKey)
    }

    private fun enableKeyspaceEvents() {
        try {
            redis.execute { connection ->
                connection.serverCommands().setConfig("notify-keyspace-events", "Ex")
                null
            }
        } catch (exception: Exception) {
            logger.warn(exception) { "Could not enable Redis keyspace expiry events." }
        }
    }

    private fun keysFor(lines: List<AllocationGatewayRedisHoldLine>): List<String> =
        lines.flatMap { listOf(RedisKeys.stock(it.sku, it.warehouse), RedisKeys.reservation(it), RedisKeys.expiry(it)) }

    private fun script(path: String): DefaultRedisScript<Long> =
        DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setLocation(ClassPathResource(path))
        }

    private fun AllocationGatewayRedisHoldLine.toScriptLine() = mapOf(
        "requestId" to requestId.toString(),
        "orderId" to orderId,
        "orderLineId" to orderLineId,
        "sku" to sku,
        "warehouse" to warehouse,
        "qty" to qty
    )

    companion object {
        fun expiredLine(expiredKey: String): AllocationGatewayRedisHoldLine? {
            val parts = expiredKey.split(':')
            if (parts.size < 5 || parts[0] != "expiry" || parts[1] != "res") return null
            val requestId = runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: return null
            return AllocationGatewayRedisHoldLine(
                requestId = requestId,
                orderId = parts[3],
                orderLineId = parts.drop(4).joinToString(":"),
                sku = "",
                warehouse = "MAIN",
                qty = 0
            )
        }
    }
}

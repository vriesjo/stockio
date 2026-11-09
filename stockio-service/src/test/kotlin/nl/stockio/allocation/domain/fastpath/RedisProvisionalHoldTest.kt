package nl.stockio.allocation.domain.fastpath

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.allocation.infra.AllocationGatewayProperties
import nl.stockio.allocation.infra.AllocationRedisGateway
import nl.stockio.allocation.infra.AllocationRedisInsufficientStockException
import nl.stockio.allocation.infra.StockAvailabilityJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class RedisProvisionalHoldTest {
    @Test
    fun `one script holds every line or none and can roll back`() {
        val redis = redis()
        val gateway = gateway(redis)
        redis.opsForValue().set("stock:sku:SKU-A:MAIN", "5")
        redis.opsForValue().set("stock:sku:SKU-B:MAIN", "1")

        org.junit.jupiter.api.Assertions.assertThrows(AllocationRedisInsufficientStockException::class.java) {
            gateway.accept(request("order-short", line("a", "SKU-A", 2), line("b", "SKU-B", 4)))
        }
        assertEquals("5", redis.opsForValue().get("stock:sku:SKU-A:MAIN"))
        assertEquals("1", redis.opsForValue().get("stock:sku:SKU-B:MAIN"))

        val request = request("order-ok", line("a", "SKU-A", 2), line("b", "SKU-A", 2))
        gateway.accept(request)
        gateway.accept(request)
        assertEquals("1", redis.opsForValue().get("stock:sku:SKU-A:MAIN"))

        gateway.rollback(request)
        assertEquals("5", redis.opsForValue().get("stock:sku:SKU-A:MAIN"))
        assertNull(redis.opsForValue().get("reservation:${request.requestId}:order-ok:a"))
    }

    private fun gateway(redis: StringRedisTemplate): AllocationRedisGateway {
        val availability = mockk<StockAvailabilityJpaRepository>()
        every { availability.sumBySkuAndWarehouse() } returns emptyList()
        val gateway = AllocationRedisGateway(
            redis,
            AllocationGatewayProperties(enabled = true, holdTtl = Duration.ofMinutes(10)),
            availability,
            jacksonObjectMapper()
        )
        gateway.rebuild()
        return gateway
    }

    private fun redis(): StringRedisTemplate {
        val connection = LettuceConnectionFactory(redisContainer.host, redisContainer.getMappedPort(6379))
        connection.afterPropertiesSet()
        return StringRedisTemplate(connection).apply { afterPropertiesSet() }
    }

    private fun request(orderId: String, vararg lines: DemandOrderLine) = AllocationRequest(
        requestId = UUID.randomUUID(),
        asOfDate = LocalDate.parse("2026-11-08"),
        runType = AllocationRunType.NEW_DEMAND,
        demandOrders = listOf(
            DemandOrder(orderId, "Northwind", 1, LocalDate.parse("2026-11-08"), lines.toList())
        )
    )

    private fun line(id: String, sku: String, qty: Int) =
        DemandOrderLine(id, sku, qty, LocalDate.parse("2026-11-08"))

    companion object {
        @Container
        @JvmStatic
        val redisContainer: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
    }
}

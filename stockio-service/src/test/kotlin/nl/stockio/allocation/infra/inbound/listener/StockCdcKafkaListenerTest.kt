package nl.stockio.allocation.infra.inbound.listener

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.stockio.allocation.infra.StockAvailabilityJpaRepository
import nl.stockio.allocation.infra.inbound.listener.StockCdcKafkaListener
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

class StockCdcKafkaListenerTest {
    private val availability = mockk<StockAvailabilityJpaRepository>()
    private val values = mockk<ValueOperations<String, String>>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>()
    private val listener = StockCdcKafkaListener(jacksonObjectMapper(), availability, redis)

    @Test
    fun `ignores a payload that does not identify a sku and warehouse`() {
        listener.receive("""{"warehouse_code":"MAIN"}""")

        verify { availability wasNot Called }
        verify { redis wasNot Called }
    }

    @Test
    fun `rewrites redis availability from the committed sum`() {
        every { redis.opsForValue() } returns values
        every { availability.sumAvailable("SKU-1", "MAIN") } returns 4L

        listener.receive("""{"payload":{"after":{"sku":"SKU-1","warehouse_code":"MAIN","quantity_available":9}}}""")

        verify { values.set("stock:sku:SKU-1:MAIN", "4") }
    }

    @Test
    fun `writes zero when the ledger has no availability row`() {
        every { redis.opsForValue() } returns values
        every { availability.sumAvailable("SKU-2", "EAST") } returns null

        listener.receive("""{"sku":"SKU-2","warehouseCode":"EAST"}""")

        verify { values.set("stock:sku:SKU-2:EAST", "0") }
    }
}

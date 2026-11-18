package nl.stockio.allocation.domain.fastpath

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.allocation.infra.AllocationRedisGateway
import nl.stockio.allocation.infra.inbound.listener.StockCdcEventParser
import nl.stockio.allocation.infra.inbound.listener.StockChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FastPathKeysTest {
    @Test
    fun `hold keys use request order line and warehouse`() {
        val requestId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val line = RedisKeys.lines(request(requestId), "MAIN").single()

        assertEquals("stock:sku:SKU-1:MAIN", RedisKeys.stock(line.sku, line.warehouse))
        assertEquals("reservation:$requestId:order-1:line-1", RedisKeys.reservation(line))
        assertEquals("expiry:res:$requestId:order-1:line-1", RedisKeys.expiry(line))
    }

    @Test
    fun `ids that contain a colon are rejected`() {
        val request = request(UUID.randomUUID()).copy(
            demandOrders = listOf(order().copy(orderId = "order:1"))
        )
        assertThrows(IllegalArgumentException::class.java) { RedisKeys.lines(request, "MAIN") }
    }

    @Test
    fun `expired key parses back to the hold identity`() {
        val requestId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val line = AllocationRedisGateway.expiredLine("expiry:res:$requestId:order-1:line-1")
        assertEquals(requestId, line?.requestId)
        assertEquals("order-1", line?.orderId)
        assertEquals("line-1", line?.orderLineId)
    }

    private fun request(requestId: UUID) = AllocationRequest(
        requestId = requestId,
        asOfDate = LocalDate.parse("2026-11-08"),
        runType = AllocationRunType.NEW_DEMAND,
        demandOrders = listOf(order())
    )

    private fun order() = DemandOrder(
        orderId = "order-1",
        customer = "Northwind",
        priority = 1,
        placedDate = LocalDate.parse("2026-11-08"),
        lines = listOf(DemandOrderLine("line-1", "SKU-1", 2, LocalDate.parse("2026-11-08")))
    )
}

class StockCdcEventParserTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `reads a debezium envelope and a flat stock event`() {
        val debezium = """{"payload":{"after":{"sku":"SKU-1","warehouse_code":"MAIN","quantity_available":4}}}"""
        val flat = """{"sku":"SKU-2","warehouseCode":"EAST"}"""

        assertEquals(StockChange("SKU-1", "MAIN"), StockCdcEventParser.parse(debezium, mapper))
        assertEquals(StockChange("SKU-2", "EAST"), StockCdcEventParser.parse(flat, mapper))
    }
}

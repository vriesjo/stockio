package nl.stockio.harness.publisher

import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.api.dto.InboundShipment
import nl.stockio.api.dto.InboundShipmentLine
import org.springframework.jdbc.core.JdbcTemplate

fun createSampleAllocationRequest(
    jdbcTemplate: JdbcTemplate,
    clock: Clock
): AllocationRequest {
    val today = LocalDate.now(clock)
    val requestId = UUID.randomUUID()
    val skus = jdbcTemplate.queryForList("select sku from products", String::class.java)
    check(skus.isNotEmpty()) { "No products found in the catalogue." }
    val demandOrders = mutableListOf<DemandOrder>()
    val inboundShipments = mutableListOf<InboundShipment>()
    for (i in 1..5) {
        val sku = skus.random()
        val quantity = listOf(1, 2, 3, 5, 8, 13, 21).random()
        val arrivalDate = today.plusDays(listOf(1L, 2L, 3L).random())
        demandOrders.add(
            DemandOrder(
                orderId = "stockio-mock-publisher-$requestId-$i",
                customer = "stockio-mock-publisher",
                priority = 1,
                placedDate = today,
                lines = listOf(
                    DemandOrderLine(
                        orderLineId = "stockio-order-line-$requestId-$i",
                        sku = sku,
                        requestedQuantity = quantity,
                        requiredDeliveryDate = today.plusDays(3)
                    )
                )
            )
        )
        inboundShipments.add(
            InboundShipment(
                shipmentId = "stockio-mock-$requestId-$i",
                lines = listOf(
                    InboundShipmentLine(
                        shipmentLineId = "stockio-mock-shipment-line-$requestId-$i",
                        sku = sku,
                        expectedArrivalDate = arrivalDate,
                        expectedQuantity = quantity,
                        warehouseCode = "MAIN"
                    )
                )
            )
        )
    }
    return AllocationRequest(
        requestId = requestId,
        asOfDate = today,
        runType = AllocationRunType.NEW_DEMAND,
        demandOrders = demandOrders,
        inboundShipments = inboundShipments
    )
}

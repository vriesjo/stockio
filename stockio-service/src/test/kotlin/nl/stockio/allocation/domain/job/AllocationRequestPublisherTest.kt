package nl.stockio.allocation.domain.job

import java.time.LocalDate
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.domain.api.data.AllocationRunType
import nl.stockio.allocation.domain.model.InboundSupplyState
import nl.stockio.allocation.domain.policy.AllocationPolicyCalculator
import nl.stockio.allocation.domain.policy.InboundAllocationPolicy
import nl.stockio.allocation.domain.policy.InventoryAllocationPolicy
import nl.stockio.allocation.domain.service.allocationLines
import nl.stockio.product.infra.ProductEntity
import nl.stockio.product.infra.ProductRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.amqp.rabbit.core.RabbitTemplate

class AllocationRequestPublisherTest {

    @Test
    fun `publishes a sample allocation request to the configured exchange`() {
        val rabbitTemplate = mock(RabbitTemplate::class.java)
        val productRepository = mock(ProductRepository::class.java)
        val product = ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer")
        `when`(productRepository.findAll()).thenReturn(listOf(product))
        val publisher = AllocationRequestPublisher(rabbitTemplate, productRepository, "allocation.exchange", "allocation.request")

        val beforePublish = LocalDate.now()
        publisher.publishSampleRequest()
        val afterPublish = LocalDate.now()

        val requestCaptor = ArgumentCaptor.forClass(AllocationRequest::class.java)
        verify(rabbitTemplate).convertAndSend(
            eq("allocation.exchange"),
            eq("allocation.request"),
            requestCaptor.capture()
        )

        val request = requestCaptor.value
        assertTrue(request.asOfDate in beforePublish..afterPublish)
        assertEquals(AllocationRunType.NEW_DEMAND, request.runType)
        assertEquals(5, request.demandOrders.size)
        assertEquals(5, request.inboundShipments.size)
        assertEquals(5, request.demandOrders.map { it.orderId }.distinct().size)
        assertEquals(5, request.demandOrders.flatMap { it.lines }.map { it.orderLineId }.distinct().size)
        assertEquals(5, request.inboundShipments.map { it.shipmentId }.distinct().size)
        assertEquals(5, request.inboundShipments.flatMap { it.lines }.map { it.shipmentLineId }.distinct().size)
        val allowedQuantities = setOf(1, 2, 3, 5, 8, 13, 21)
        request.demandOrders.forEach { order ->
            val line = order.lines.single()
            assertEquals(product.sku, line.sku)
            assertTrue(line.requestedQuantity in allowedQuantities)
            assertEquals(request.asOfDate, order.placedDate)
            assertEquals(request.asOfDate.plusDays(3), line.requiredDeliveryDate)
        }
        request.inboundShipments.forEach { shipment ->
            val line = shipment.lines.single()
            assertEquals(product.sku, line.sku)
            assertTrue(line.expectedQuantity in allowedQuantities)
            assertTrue(
                line.expectedArrivalDate in request.asOfDate.plusDays(1)..request.asOfDate.plusDays(3)
            )
            assertEquals("MAIN", line.warehouseCode)
        }
        request.demandOrders.zip(request.inboundShipments).forEach { (order, shipment) ->
            assertEquals(order.lines.single().requestedQuantity, shipment.lines.single().expectedQuantity)
            assertTrue(shipment.lines.single().expectedArrivalDate <= order.lines.single().requiredDeliveryDate)
        }

        val calculation = AllocationPolicyCalculator(InventoryAllocationPolicy(), InboundAllocationPolicy()).calculate(
            orders = request.allocationLines(),
            asOfDate = request.asOfDate,
            inventory = emptyList(),
            inboundSupply = request.inboundShipments.flatMap { shipment ->
                shipment.lines.map { line ->
                    InboundSupplyState(
                        shipmentId = shipment.shipmentId,
                        shipmentLineId = line.shipmentLineId,
                        sku = line.sku,
                        warehouseCode = line.warehouseCode,
                        expectedQuantity = line.expectedQuantity,
                        expectedArrivalDate = line.expectedArrivalDate,
                        quantityReserved = 0
                    )
                }
            },
            stockReservations = emptyList(),
            inboundReservations = emptyList(),
            registerBackorders = true
        )
        calculation.allocations.forEach { allocation ->
            assertEquals(allocation.requestedQuantity, allocation.allocatedFromInbound)
            assertEquals(0, allocation.backorderedQuantity)
            assertEquals(
                allocation.requestedQuantity,
                calculation.inboundReservations.filter { it.orderLineId == allocation.orderLineId }.sumOf { it.quantity }
            )
        }
    }
}

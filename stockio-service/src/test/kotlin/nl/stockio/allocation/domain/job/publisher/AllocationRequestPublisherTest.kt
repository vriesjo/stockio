package nl.stockio.allocation.domain.job.publisher

import java.time.Clock
import java.time.LocalDate
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.AllocationRunType
import nl.stockio.allocation.domain.model.InboundSupplyState
import nl.stockio.allocation.domain.service.allocationLines
import nl.stockio.allocation.domain.worker.AllocationCalculator
import nl.stockio.allocation.domain.worker.InboundAllocationPolicy
import nl.stockio.allocation.domain.worker.InventoryAllocationPolicy
import nl.stockio.product.domain.port.ProductRepository
import nl.stockio.product.infra.ProductEntity
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.amqp.rabbit.core.RabbitTemplate

class AllocationRequestPublisherTest {

    @Test
    fun `publishes a sample allocation request to the configured exchange`() {
        val rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
        val productRepository = Mockito.mock(ProductRepository::class.java)
        val product = ProductEntity(sku = "MAKI-KOMKOMMER", title = "Maki Komkommer")
        Mockito.`when`(productRepository.findAll()).thenReturn(listOf(product))
        val publisher = AllocationRequestPublisher(
            rabbitTemplate, productRepository, Clock.systemDefaultZone(), "allocation.exchange", "allocation.request"
        )

        val beforePublish = LocalDate.now()
        publisher.publishSampleRequest()
        val afterPublish = LocalDate.now()

        val requestCaptor = ArgumentCaptor.forClass(AllocationRequest::class.java)
        Mockito.verify(rabbitTemplate).convertAndSend(
            ArgumentMatchers.eq("allocation.exchange"),
            ArgumentMatchers.eq("allocation.request"),
            requestCaptor.capture()
        )

        val request = requestCaptor.value
        Assertions.assertTrue(request.asOfDate in beforePublish..afterPublish)
        Assertions.assertEquals(AllocationRunType.NEW_DEMAND, request.runType)
        Assertions.assertEquals(5, request.demandOrders.size)
        Assertions.assertEquals(5, request.inboundShipments.size)
        Assertions.assertEquals(5, request.demandOrders.map { it.orderId }.distinct().size)
        Assertions.assertEquals(5, request.demandOrders.flatMap { it.lines }.map { it.orderLineId }.distinct().size)
        Assertions.assertEquals(5, request.inboundShipments.map { it.shipmentId }.distinct().size)
        Assertions.assertEquals(
            5,
            request.inboundShipments.flatMap { it.lines }.map { it.shipmentLineId }.distinct().size
        )
        val allowedQuantities = setOf(1, 2, 3, 5, 8, 13, 21)
        request.demandOrders.forEach { order ->
            val line = order.lines.single()
            Assertions.assertEquals(product.sku, line.sku)
            Assertions.assertTrue(line.requestedQuantity in allowedQuantities)
            Assertions.assertEquals(request.asOfDate, order.placedDate)
            Assertions.assertEquals(request.asOfDate.plusDays(3), line.requiredDeliveryDate)
        }
        request.inboundShipments.forEach { shipment ->
            val line = shipment.lines.single()
            Assertions.assertEquals(product.sku, line.sku)
            Assertions.assertTrue(line.expectedQuantity in allowedQuantities)
            Assertions.assertTrue(
                line.expectedArrivalDate in request.asOfDate.plusDays(1)..request.asOfDate.plusDays(3)
            )
            Assertions.assertEquals("MAIN", line.warehouseCode)
        }
        request.demandOrders.zip(request.inboundShipments).forEach { (order, shipment) ->
            Assertions.assertEquals(order.lines.single().requestedQuantity, shipment.lines.single().expectedQuantity)
            Assertions.assertTrue(shipment.lines.single().expectedArrivalDate <= order.lines.single().requiredDeliveryDate)
        }

        val calculation = AllocationCalculator(InventoryAllocationPolicy(), InboundAllocationPolicy()).calculate(
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
            Assertions.assertEquals(allocation.requestedQuantity, allocation.allocatedFromInbound)
            Assertions.assertEquals(0, allocation.backorderedQuantity)
            Assertions.assertEquals(
                allocation.requestedQuantity,
                calculation.inboundReservations.filter { it.orderLineId == allocation.orderLineId }
                    .sumOf { it.quantity }
            )
        }
    }
}

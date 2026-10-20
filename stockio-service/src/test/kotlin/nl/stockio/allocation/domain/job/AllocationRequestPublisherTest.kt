package nl.stockio.allocation.domain.job

import java.time.LocalDate
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.domain.api.data.AllocationRunType
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
        val allowedQuantities = setOf(1, 2, 3, 5, 8, 13, 21)
        request.demandOrders.forEach { order ->
            assertEquals(product.sku, order.sku)
            assertTrue(order.requestedQuantity in allowedQuantities)
            assertEquals(request.asOfDate, order.placedDate)
            assertEquals(request.asOfDate, order.requiredDeliveryDate)
        }
        request.inboundShipments.forEach { shipment ->
            assertEquals(product.sku, shipment.sku)
            assertTrue(shipment.expectedQuantity in allowedQuantities)
            assertTrue(
                shipment.expectedArrivalDate in request.asOfDate.plusDays(1)..request.asOfDate.plusDays(3)
            )
            assertEquals("MAIN", shipment.warehouseCode)
        }
    }
}

package nl.stockio.allocation.domain.job

import java.time.LocalDate
import nl.stockio.allocation.domain.bean.AllocationRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.amqp.rabbit.core.RabbitTemplate

class AllocationRequestPublisherTest {

    @Test
    fun `publishes a sample allocation request to the configured exchange`() {
        val rabbitTemplate = mock(RabbitTemplate::class.java)
        val publisher = AllocationRequestPublisher(rabbitTemplate, "allocation.exchange", "allocation.request")

        publisher.publishSampleRequest()

        val requestCaptor = ArgumentCaptor.forClass(AllocationRequest::class.java)
        verify(rabbitTemplate).convertAndSend(
            eq("allocation.exchange"),
            eq("allocation.request"),
            requestCaptor.capture()
        )

        val request = requestCaptor.value
        assertEquals(LocalDate.now(), request.asOfDate)
        assertEquals(1, request.demandOrders.size)
        assertEquals("MAKI-ZALM", request.demandOrders.single().sku)
        assertEquals(1, request.demandOrders.single().requestedQuantity)
    }
}

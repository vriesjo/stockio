package nl.stockio.harness.publisher

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate

class AllocationRequestPublisherTest {

    private val clock = Clock.fixed(Instant.parse("2026-03-16T23:30:00Z"), ZoneId.of("Europe/Amsterdam"))

    @Test
    fun `publishes a sample allocation request to the configured exchange`() {
        val rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbcTemplate.queryForList("select sku from products", String::class.java))
            .thenReturn(listOf("MAKI-KOMKOMMER"))
        val publisher = AllocationRequestPublisher(
            rabbitTemplate, jdbcTemplate, clock, "allocation.exchange", "allocation.request"
        )

        publisher.publishSampleRequest()

        val requestCaptor = ArgumentCaptor.forClass(AllocationRequest::class.java)
        Mockito.verify(rabbitTemplate).convertAndSend(
            ArgumentMatchers.eq("allocation.exchange"),
            ArgumentMatchers.eq("allocation.request"),
            requestCaptor.capture()
        )

        val request = requestCaptor.value
        Assertions.assertEquals(LocalDate.of(2026, 3, 17), request.asOfDate)
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
            Assertions.assertEquals("MAKI-KOMKOMMER", line.sku)
            Assertions.assertEquals("stockio-mock-publisher", order.customer)
            Assertions.assertEquals(1, order.priority)
            Assertions.assertTrue(line.requestedQuantity in allowedQuantities)
            Assertions.assertEquals(request.asOfDate, order.placedDate)
            Assertions.assertEquals(request.asOfDate.plusDays(3), line.requiredDeliveryDate)
        }
        request.inboundShipments.forEach { shipment ->
            val line = shipment.lines.single()
            Assertions.assertEquals("MAKI-KOMKOMMER", line.sku)
            Assertions.assertTrue(line.expectedQuantity in allowedQuantities)
            Assertions.assertTrue(
                line.expectedArrivalDate in request.asOfDate.plusDays(1)..request.asOfDate.plusDays(3)
            )
            Assertions.assertEquals("MAIN", line.warehouseCode)
        }
        request.demandOrders.zip(request.inboundShipments).forEach { (order, shipment) ->
            Assertions.assertEquals(order.lines.single().sku, shipment.lines.single().sku)
            Assertions.assertEquals(order.lines.single().requestedQuantity, shipment.lines.single().expectedQuantity)
            Assertions.assertTrue(shipment.lines.single().expectedArrivalDate <= order.lines.single().requiredDeliveryDate)
        }

        val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val converter = AllocationPublisherApplication().allocationJsonMessageConverter(objectMapper)
        val message = converter.toMessage(request, MessageProperties())
        val json = objectMapper.readTree(message.body)
        Assertions.assertEquals("application/json", message.messageProperties.contentType)
        Assertions.assertEquals("nl.stockio.api.dto.AllocationRequest", message.messageProperties.headers["__TypeId__"])
        Assertions.assertEquals("NEW_DEMAND", json["runType"].asText())
        Assertions.assertEquals("2026-03-17", json["asOfDate"].asText())
        Assertions.assertEquals("2026-03-20", json["demandOrders"][0]["lines"][0]["requiredDeliveryDate"].asText())
        Assertions.assertEquals(request, converter.fromMessage(message))
    }

    @Test
    fun `does not send a request when the catalogue is empty or unavailable`() {
        val rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbcTemplate.queryForList("select sku from products", String::class.java))
            .thenReturn(emptyList())
            .thenThrow(DataAccessResourceFailureException("Catalogue unavailable"))
        val publisher = AllocationRequestPublisher(
            rabbitTemplate, jdbcTemplate, clock, "allocation.exchange", "allocation.request"
        )

        Assertions.assertThrows(IllegalStateException::class.java, publisher::publishSampleRequest)
        Assertions.assertThrows(DataAccessResourceFailureException::class.java, publisher::publishSampleRequest)
        Mockito.verifyNoInteractions(rabbitTemplate)
    }

    @Test
    fun `does not hide RabbitMQ send failures`() {
        val rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbcTemplate.queryForList("select sku from products", String::class.java))
            .thenReturn(listOf("MAKI-KOMKOMMER"))
        Mockito.doThrow(AmqpException("Broker unavailable")).`when`(rabbitTemplate).convertAndSend(
            ArgumentMatchers.eq("allocation.exchange"),
            ArgumentMatchers.eq("allocation.request"),
            ArgumentMatchers.any(AllocationRequest::class.java)
        )
        val publisher = AllocationRequestPublisher(
            rabbitTemplate, jdbcTemplate, clock, "allocation.exchange", "allocation.request"
        )

        Assertions.assertThrows(AmqpException::class.java, publisher::publishSampleRequest)
    }

    @Test
    fun `requires explicit enablement before registering the publisher`() {
        val rabbitTemplate = Mockito.mock(RabbitTemplate::class.java)
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        val runner = ApplicationContextRunner()
            .withUserConfiguration(AllocationRequestPublisher::class.java)
            .withBean(RabbitTemplate::class.java, { rabbitTemplate })
            .withBean(JdbcTemplate::class.java, { jdbcTemplate })
            .withBean(Clock::class.java, { clock })
            .withPropertyValues(
                "stockio.allocation.rabbitmq.exchange=allocation.exchange",
                "stockio.allocation.rabbitmq.routing-key=allocation.request"
            )
        runner.run { context ->
            Assertions.assertTrue(context.getBeansOfType(AllocationRequestPublisher::class.java).isEmpty())
        }
        runner.withPropertyValues("stockio.allocation.rabbitmq.publisher.enabled=false").run { context ->
            Assertions.assertTrue(context.getBeansOfType(AllocationRequestPublisher::class.java).isEmpty())
        }
        runner.withPropertyValues("stockio.allocation.rabbitmq.publisher.enabled=true").run { context ->
            Assertions.assertNotNull(context.getBean(AllocationRequestPublisher::class.java))
        }
        Mockito.verify(jdbcTemplate, Mockito.never()).queryForList("select sku from products", String::class.java)
        Mockito.verify(rabbitTemplate, Mockito.never()).convertAndSend(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(AllocationRequest::class.java)
        )
    }
}

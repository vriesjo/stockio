package nl.stockio.harness.publisher

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult

class AllocationKafkaRequestPublisherTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val clock = Clock.fixed(Instant.parse("2026-03-16T23:30:00Z"), ZoneId.of("Europe/Amsterdam"))

    @Test
    fun `publishes a sample allocation request to the configured Kafka topic`() {
        val kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbcTemplate.queryForList("select sku from products", String::class.java))
            .thenReturn(listOf("MAKI-KOMKOMMER"))
        val sendResult = Mockito.mock(SendResult::class.java) as SendResult<String, String>
        Mockito.`when`(
            kafkaTemplate.send(
                ArgumentMatchers.eq("stockio.allocation.requests"),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()
            )
        ).thenReturn(CompletableFuture.completedFuture(sendResult))

        val publisher = AllocationKafkaRequestPublisher(
            kafkaTemplate, objectMapper, jdbcTemplate, clock, "stockio.allocation.requests"
        )

        publisher.publishSampleRequest()

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(kafkaTemplate).send(
            ArgumentMatchers.eq("stockio.allocation.requests"),
            keyCaptor.capture(),
            payloadCaptor.capture()
        )

        val payload = objectMapper.readTree(payloadCaptor.value)
        Assertions.assertEquals(keyCaptor.value, payload["requestId"].asText())
        Assertions.assertEquals("NEW_DEMAND", payload["runType"].asText())
        Assertions.assertEquals("2026-03-17", payload["asOfDate"].asText())
        Assertions.assertEquals(5, payload["demandOrders"].size())
        Assertions.assertEquals(5, payload["inboundShipments"].size())
        Assertions.assertEquals("stockio-mock-publisher", payload["demandOrders"][0]["customer"].asText())
    }

    @Test
    fun `does not send a request when the catalogue is empty or unavailable`() {
        val kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbcTemplate.queryForList("select sku from products", String::class.java))
            .thenReturn(emptyList())
            .thenThrow(DataAccessResourceFailureException("Catalogue unavailable"))
        val publisher = AllocationKafkaRequestPublisher(
            kafkaTemplate, objectMapper, jdbcTemplate, clock, "stockio.allocation.requests"
        )

        Assertions.assertThrows(IllegalStateException::class.java, publisher::publishSampleRequest)
        Assertions.assertThrows(DataAccessResourceFailureException::class.java, publisher::publishSampleRequest)
        Mockito.verifyNoInteractions(kafkaTemplate)
    }

    @Test
    fun `does not hide Kafka send failures`() {
        val kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        Mockito.`when`(jdbcTemplate.queryForList("select sku from products", String::class.java))
            .thenReturn(listOf("MAKI-KOMKOMMER"))
        Mockito.`when`(
            kafkaTemplate.send(
                ArgumentMatchers.eq("stockio.allocation.requests"),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()
            )
        ).thenReturn(CompletableFuture.failedFuture(IllegalStateException("Broker unavailable")))
        val publisher = AllocationKafkaRequestPublisher(
            kafkaTemplate, objectMapper, jdbcTemplate, clock, "stockio.allocation.requests"
        )

        val exception = Assertions.assertThrows(IllegalStateException::class.java, publisher::publishSampleRequest)
        Assertions.assertEquals("Broker unavailable", exception.message)
    }

    @Test
    fun `requires explicit enablement before registering the Kafka publisher`() {
        val kafkaTemplate = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
        val runner = ApplicationContextRunner()
            .withUserConfiguration(AllocationKafkaRequestPublisher::class.java)
            .withBean(KafkaTemplate::class.java, { kafkaTemplate })
            .withBean(JdbcTemplate::class.java, { jdbcTemplate })
            .withBean(Clock::class.java, { clock })
            .withBean(com.fasterxml.jackson.databind.ObjectMapper::class.java, { objectMapper })
            .withPropertyValues("stockio.allocation.kafka.topic=stockio.allocation.requests")

        runner.run { context ->
            Assertions.assertTrue(context.getBeansOfType(AllocationKafkaRequestPublisher::class.java).isEmpty())
        }
        runner.withPropertyValues("stockio.allocation.kafka.publisher.enabled=false").run { context ->
            Assertions.assertTrue(context.getBeansOfType(AllocationKafkaRequestPublisher::class.java).isEmpty())
        }
        runner.withPropertyValues("stockio.allocation.kafka.publisher.enabled=true").run { context ->
            Assertions.assertNotNull(context.getBean(AllocationKafkaRequestPublisher::class.java))
        }
    }
}

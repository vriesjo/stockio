package nl.stockio.harness.publisher

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.util.concurrent.ExecutionException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import nl.stockio.api.dto.AllocationRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(
    prefix = "stockio.allocation.kafka.publisher",
    name = ["enabled"],
    havingValue = "true"
)
class AllocationKafkaRequestPublisher(
    private val kafkaTemplate: KafkaTemplate<String, AllocationRequest>,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    @Value("\${stockio.allocation.kafka.topic:stockio.allocation.requests}") private val topic: String
) {

    @Scheduled(
        fixedRateString = "\${stockio.allocation.kafka.publisher.interval:1000}",
        initialDelayString = "\${stockio.allocation.kafka.publisher.interval:1000}"
    )
    fun publishSampleRequest() {
        val request = createSampleAllocationRequest(jdbcTemplate, clock)
        try {
            kafkaTemplate.send(topic, request.requestId.toString(), request).get()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
        logger.info {
            "Published sample allocation request '${request.requestId}' to Kafka topic '$topic'."
        }
    }
}

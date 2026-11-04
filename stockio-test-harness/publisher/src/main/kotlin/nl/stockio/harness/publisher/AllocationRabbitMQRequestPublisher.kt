package nl.stockio.harness.publisher

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
@ConditionalOnProperty(
    prefix = "stockio.allocation.rabbitmq.publisher",
    name = ["enabled"],
    havingValue = "true"
)
class AllocationRequestPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
    @Value("\${stockio.allocation.rabbitmq.exchange}") private val exchange: String,
    @Value("\${stockio.allocation.rabbitmq.routing-key}") private val routingKey: String
) {

    @Scheduled(
        fixedRateString = "\${stockio.allocation.rabbitmq.publisher.interval:1000}",
        initialDelayString = "\${stockio.allocation.rabbitmq.publisher.interval:1000}"
    )
    fun publishSampleRequest() {
        val request = createSampleAllocationRequest(jdbcTemplate, clock)

        rabbitTemplate.convertAndSend(exchange, routingKey, request)
        logger.info { "Published sample allocation request '${request.requestId}' to RabbitMQ." }
    }
}

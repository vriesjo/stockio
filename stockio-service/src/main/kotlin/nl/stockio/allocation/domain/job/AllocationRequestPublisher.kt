package nl.stockio.allocation.domain.job

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunType
import nl.stockio.allocation.domain.bean.DemandOrder
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
    @Value("\${stockio.allocation.rabbitmq.exchange}") private val exchange: String,
    @Value("\${stockio.allocation.rabbitmq.routing-key}") private val routingKey: String
) {

    @Scheduled(
        fixedRateString = "\${stockio.allocation.rabbitmq.publisher.interval:1000}",
        initialDelayString = "\${stockio.allocation.rabbitmq.publisher.interval:1000}"
    )
    fun publishSampleRequest() {
        val today = LocalDate.now()
        val requestId = UUID.randomUUID()
        val request = AllocationRequest(
            requestId = requestId,
            asOfDate = today,
            runType = AllocationRunType.NEW_DEMAND,
            demandOrders = listOf(
                DemandOrder(
                    orderId = "rabbit-demo-$requestId",
                    customer = "RabbitMQ Demo",
                    sku = SAMPLE_SKU,
                    requestedQuantity = 1,
                    priority = 1,
                    placedDate = today,
                    requiredDeliveryDate = today
                )
            )
        )

        rabbitTemplate.convertAndSend(exchange, routingKey, request)
        logger.info { "Published sample allocation request '$requestId' to RabbitMQ." }
    }

    private companion object {
        const val SAMPLE_SKU = "MAKI-ZALM"
    }
}

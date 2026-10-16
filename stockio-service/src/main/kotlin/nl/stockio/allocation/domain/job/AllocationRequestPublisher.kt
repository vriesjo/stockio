package nl.stockio.allocation.domain.job

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunType
import nl.stockio.allocation.domain.bean.DemandOrder
import nl.stockio.allocation.domain.bean.InboundShipment
import nl.stockio.product.infra.ProductRepository
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
    private val productRepository: ProductRepository,
    @Value("\${stockio.allocation.rabbitmq.exchange}") private val exchange: String,
    @Value("\${stockio.allocation.rabbitmq.routing-key}") private val routingKey: String
) {

    @Scheduled(
        fixedRateString = "\${stockio.allocation.rabbitmq.publisher.interval:250}",
        initialDelayString = "\${stockio.allocation.rabbitmq.publisher.interval:250}"
    )
    fun publishSampleRequest() {
        val today = LocalDate.now()
        val requestId = UUID.randomUUID()
        val products = productRepository.findAll().toList()
        val demandOrders = mutableListOf<DemandOrder>()
        val inboundShipments = mutableListOf<InboundShipment>() // Placeholder for inbound shipments if needed
        for (i in 1..5) {
            val product = products.randomOrNull() ?: error("No products found in the repository.")
            demandOrders.add(
                DemandOrder(
                    orderId = "rabbit-demo-$requestId",
                    customer = "RabbitMQ Demo",
                    sku = product.sku,
                    requestedQuantity = listOf(1, 2, 3, 5, 8, 13, 21).random(),
                    priority = 1,
                    placedDate = today,
                    requiredDeliveryDate = today
                )
            )
            inboundShipments.add(
                InboundShipment(
                    shipmentId = "rabbit-demo-$requestId",
                    sku = product.sku,
                    expectedArrivalDate = today.plusDays(listOf(1L, 2L, 3L).random()),
                    expectedQuantity = listOf(1, 2, 3, 5, 8, 13, 21).random(),
                    warehouseCode = "MAIN"
                )
            )
        }


        val request = AllocationRequest(
            requestId = requestId,
            asOfDate = today,
            runType = AllocationRunType.NEW_DEMAND,
            demandOrders = demandOrders,
            inboundShipments = inboundShipments
        )

        rabbitTemplate.convertAndSend(exchange, routingKey, request)
        logger.info { "Published sample allocation request '$requestId' to RabbitMQ." }
    }

    private companion object {
        const val SAMPLE_SKU = "MAKI-ZALM"
    }
}

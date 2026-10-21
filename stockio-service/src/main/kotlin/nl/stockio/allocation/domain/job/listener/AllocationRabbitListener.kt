package nl.stockio.allocation.domain.job.listener

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.job.AllocationJob
import nl.stockio.allocation.domain.api.dto.AllocationRequestEvent
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.amqp.rabbit.annotation.EnableRabbit
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.amqp.support.converter.MessageConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class AllocationRabbitListener(
    private val allocationJob: AllocationJob,
    private val allocationEventListener: AllocationEventListener,
    private val storageProvider: StorageProvider
) {

    @RabbitListener(queues = ["\${stockio.allocation.rabbitmq.request-queue}"])
    fun receive(request: AllocationRequest) {
        val jobId = request.requestId
        try {
            allocationJob.execute(jobId, request)
            logger.info { "Processed allocation request '${request.requestId}' synchronously." }
        } catch (exception: IllegalArgumentException) {
            logger.error(exception) {
                "Allocation request '${request.requestId}' is invalid; rejecting it to the dead-letter queue."
            }
            throw exception
        } catch (exception: Exception) {
            logger.error(exception) {
                "Synchronous processing of allocation request '${request.requestId}' failed; enqueueing JobRunr fallback."
            }
            val existingJob = try {
                storageProvider.getJobById(jobId)
            } catch (_: JobNotFoundException) {
                null
            }
            if (existingJob != null) {
                logger.info { "JobRunr job '${jobId}' already exists; skipping enqueue." }
                return
            }
            allocationEventListener.enqueue(AllocationRequestEvent(jobId, request))
        }
    }
}

@EnableRabbit
@EnableScheduling
@Configuration
class AllocationRabbitConfiguration {

    @Bean
    fun allocationRequestExchange(
        @Value("\${stockio.allocation.rabbitmq.exchange}") exchange: String
    ): TopicExchange = TopicExchange(exchange, true, false)

    @Bean
    fun allocationRequestQueue(
        @Value("\${stockio.allocation.rabbitmq.request-queue}") queue: String,
        @Value("\${stockio.allocation.rabbitmq.dead-letter-exchange}") deadLetterExchange: String,
        @Value("\${stockio.allocation.rabbitmq.dead-letter-routing-key}") deadLetterRoutingKey: String
    ): Queue = QueueBuilder.durable(queue)
        .deadLetterExchange(deadLetterExchange)
        .deadLetterRoutingKey(deadLetterRoutingKey)
        .build()

    @Bean
    fun allocationRequestBinding(
        allocationRequestQueue: Queue,
        allocationRequestExchange: TopicExchange,
        @Value("\${stockio.allocation.rabbitmq.routing-key}") routingKey: String
    ): Binding = BindingBuilder.bind(allocationRequestQueue).to(allocationRequestExchange).with(routingKey)

    @Bean
    fun allocationDeadLetterExchange(
        @Value("\${stockio.allocation.rabbitmq.dead-letter-exchange}") exchange: String
    ): DirectExchange = DirectExchange(exchange, true, false)

    @Bean
    fun allocationDeadLetterQueue(
        @Value("\${stockio.allocation.rabbitmq.dead-letter-queue}") queue: String
    ): Queue = QueueBuilder.durable(queue).build()

    @Bean
    fun allocationDeadLetterBinding(
        allocationDeadLetterQueue: Queue,
        allocationDeadLetterExchange: DirectExchange,
        @Value("\${stockio.allocation.rabbitmq.dead-letter-routing-key}") routingKey: String
    ): Binding = BindingBuilder.bind(allocationDeadLetterQueue).to(allocationDeadLetterExchange).with(routingKey)

    @Bean
    fun allocationJsonMessageConverter(objectMapper: ObjectMapper): MessageConverter =
        Jackson2JsonMessageConverter(objectMapper)
}

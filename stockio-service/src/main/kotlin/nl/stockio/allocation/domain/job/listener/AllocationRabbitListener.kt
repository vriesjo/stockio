package nl.stockio.allocation.domain.job.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.Channel
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.service.AllocationAdmissionProperties
import nl.stockio.allocation.domain.service.AllocationCapacityExceededException
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.allocation.domain.service.AllocationRequestTooLargeException
import org.jobrunr.scheduling.JobScheduler
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
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.ImmediateRequeueAmqpException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer
import org.springframework.amqp.support.converter.MessageConversionException
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
    private val jobScheduler: JobScheduler,
    private val allocationJobExecutor: AllocationJobExecutor,
    private val storageProvider: StorageProvider,
    private val capacityBackoff: AllocationRabbitCapacityBackoff
) {

    @RabbitListener(queues = ["\${stockio.allocation.rabbitmq.request-queue}"],
        containerFactory = "allocationRabbitListenerContainerFactory")
    fun receive(request: AllocationRequest, channel: Channel) {
        while (true) {
            if (!channel.isOpen) throw ImmediateRequeueAmqpException("Allocation consumer channel closed.")
            try {
                process(request)
                return
            } catch (_: AllocationCapacityExceededException) {
                capacityBackoff.pause()
            }
        }
    }

    private fun process(request: AllocationRequest) {
        val jobId = request.requestId
        try {
            allocationJobExecutor.executeSync(jobId, request)
            logger.info { "Processed allocation request '${request.requestId}' synchronously." }
        } catch (exception: AllocationCapacityExceededException) {
            throw exception
        } catch (exception: AllocationRequestTooLargeException) {
            throw AmqpRejectAndDontRequeueException("Allocation request exceeds the byte limit.", exception)
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
            jobScheduler.enqueue(jobId) {
                logger.info { "Async executing allocation job ${jobId} for request ${request.requestId}, " +
                    "only when the synchronous processing fails." }
                allocationJobExecutor.executeSync(jobId, request)
            }
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
    fun allocationJsonMessageConverter(
        objectMapper: ObjectMapper,
        limits: AllocationRequestLimits
    ): MessageConverter = object : Jackson2JsonMessageConverter(objectMapper) {
        override fun fromMessage(message: Message, conversionHint: Any?): Any {
            try {
                limits.checkBytes(message.body.size.toLong())
            } catch (exception: AllocationRequestTooLargeException) {
                throw MessageConversionException(exception.message.orEmpty(), exception)
            }
            return super.fromMessage(message, conversionHint)
        }
    }

    @Bean
    fun allocationRabbitListenerContainerFactory(
        configurer: SimpleRabbitListenerContainerFactoryConfigurer,
        connectionFactory: ConnectionFactory,
        properties: AllocationAdmissionProperties
    ): SimpleRabbitListenerContainerFactory =
        SimpleRabbitListenerContainerFactory().also { factory ->
            configurer.configure(factory, connectionFactory)
            if (properties.enabled) {
                factory.setAcknowledgeMode(AcknowledgeMode.AUTO)
                factory.setPrefetchCount(1)
                factory.setConcurrentConsumers(1)
                factory.setMaxConcurrentConsumers(1)
            }
        }
}

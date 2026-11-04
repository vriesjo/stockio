package nl.stockio.allocation.domain.job.listener

import java.time.Duration
import nl.stockio.allocation.domain.service.AllocationRequestTooLargeException
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

/**
 * Configuration for Kafka allocation request processing.
 * This configuration sets up the necessary beans and properties for handling allocation requests via Kafka.
 * It includes properties for topics, concurrency, backoff strategies, and error handling.
 */
@ConfigurationProperties("stockio.allocation.kafka")
data class AllocationKafkaProperties(
    val enabled: Boolean = false,
    val topic: String = "stockio.allocation.requests",
    val groupId: String = "stockio-allocation",
    val deadLetterTopic: String = "$topic.DLT",
    val partitionCount: Int = 4,
    val listenerConcurrency: Int = 2,
    val workerCount: Int = 2,
    val channelCapacity: Int = 4,
    val capacityBackoffMin: Duration = Duration.ofSeconds(5),
    val capacityBackoffMax: Duration = Duration.ofSeconds(10),
    val retryBackoff: Duration = Duration.ofSeconds(1),
    val retryAttempts: Long = 3
) {
    init {
        require(topic.isNotBlank() && groupId.isNotBlank() && deadLetterTopic.isNotBlank()) {
            "Kafka allocation topic, group ID, and dead-letter topic must not be blank."
        }
        require(topic != deadLetterTopic) { "Kafka allocation and dead-letter topics must differ." }
        require(partitionCount > 0 && listenerConcurrency > 0 && workerCount > 0 && channelCapacity > 0) {
            "Kafka allocation partitions, concurrency, workers, and channel capacity must be positive."
        }
        require(!enabled || listenerConcurrency <= partitionCount) {
            "Kafka allocation listener concurrency cannot exceed the topic partition count."
        }
        require(
            capacityBackoffMin.toMillis() > 0 &&
                capacityBackoffMax >= capacityBackoffMin
        ) {
            "Kafka allocation capacity backoff must be positive and ordered."
        }
        require(retryBackoff.toMillis() > 0 && retryAttempts >= 0) {
            "Kafka allocation retry backoff and attempts must not be negative."
        }
    }
}

@Configuration
@EnableKafka
@EnableConfigurationProperties(AllocationKafkaProperties::class)
class AllocationKafkaConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "stockio.allocation.kafka", name = ["enabled"], havingValue = "true")
    fun allocationKafkaAdmin(kafkaProperties: KafkaProperties): KafkaAdmin =
        KafkaAdmin(kafkaProperties.buildAdminProperties(null))

    /**
        The ConcurrentKafkaListenerContainerFactoryConfigurer is used to configure the Kafka listener container factory with the necessary properties and settings.
        The ConsumerFactory is responsible for creating Kafka consumer instances that will be used by the listener.
        The DefaultErrorHandler is used to handle errors that occur during message processing, including retries and dead-letter handling.
     */
    @Bean
    fun allocationKafkaListenerContainerFactory(
        configurer: ConcurrentKafkaListenerContainerFactoryConfigurer,
        consumerFactory: ConsumerFactory<Any, Any>,
        errorHandler: DefaultErrorHandler
    ): ConcurrentKafkaListenerContainerFactory<Any, Any> =
        ConcurrentKafkaListenerContainerFactory<Any, Any>().also { factory ->
            configurer.configure(factory, consumerFactory)
            factory.setCommonErrorHandler(errorHandler)
        }

    /**
        The DefaultErrorHandler is configured to handle errors that occur during message processing.
        It uses a DeadLetterPublishingRecoverer to publish failed messages to a dead-letter topic after the specified number of retry attempts.
        The FixedBackOff is used to specify the backoff strategy for retries, including the interval between retries and the maximum number of attempts.
     */
    @Bean
    fun allocationKafkaTemplate(kafkaProperties: KafkaProperties): KafkaTemplate<String, String> {
        val producerProperties = kafkaProperties.buildProducerProperties(null)
        producerProperties[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        producerProperties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return KafkaTemplate(DefaultKafkaProducerFactory(producerProperties))
    }

    /**
     * The allocationKafkaErrorHandler bean defines a DefaultErrorHandler that handles errors during Kafka message processing.
     * It uses a DeadLetterPublishingRecoverer to publish failed messages to a dead-letter topic after the specified number of retry attempts.
     * The FixedBackOff is used to specify the backoff strategy for retries, including the interval between retries and the maximum number of attempts.
     * The error handler is configured to not retry for specific exceptions, such as AllocationRequestTooLargeException and IllegalArgumentException, which are considered non-retryable.
     * This ensures that messages that cannot be processed due to these exceptions are immediately sent to the dead-letter topic without retrying.
     */
    @Bean
    fun allocationKafkaErrorHandler(
        template: KafkaTemplate<String, String>,
        properties: AllocationKafkaProperties
    ): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(template) { record, _ ->
            TopicPartition(properties.deadLetterTopic, record.partition())
        }.also { it.setFailIfSendResultIsError(true) }
        return DefaultErrorHandler(
            recoverer,
            FixedBackOff(properties.retryBackoff.toMillis(), properties.retryAttempts)
        ).also {
            it.addNotRetryableExceptions(
                AllocationRequestTooLargeException::class.java,
                IllegalArgumentException::class.java
            )
        }
    }

    /**
        The allocationKafkaTopics bean defines the Kafka topics used for allocation requests and dead-letter handling.
        It creates a NewTopic for the main allocation request topic and a NewTopic for the dead-letter topic, both with the specified number of partitions and a replication factor of 1.
        This ensures that the necessary topics are created in the Kafka cluster when the application starts, allowing for proper message routing and handling.
     */
    @Bean
    @ConditionalOnProperty(prefix = "stockio.allocation.kafka", name = ["enabled"], havingValue = "true")
    fun allocationKafkaTopics(properties: AllocationKafkaProperties): KafkaAdmin.NewTopics =
        KafkaAdmin.NewTopics(
            topic(properties.topic, properties.partitionCount),
            topic(properties.deadLetterTopic, properties.partitionCount)
        )

    // The topic function creates a NewTopic with the specified name and number of partitions,
    // along with a replication factor of 3 and a minimum in-sync replicas configuration of 2.
    // Replication factor of 3 ensures that there are three copies of the topic's data in the Kafka cluster, providing fault tolerance and high availability.
    // Minimum in-sync replicas configuration of 2 ensures that at least two replicas must be in sync for the topic to be considered healthy,
    // preventing data loss in case of broker failures.
    private fun topic(name: String, partitions: Int): NewTopic =
        TopicBuilder.name(name)
            .partitions(partitions)
            .replicas(3)
            .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
            .build()
}

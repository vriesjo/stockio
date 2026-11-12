package nl.stockio.allocation.domain.job.listener

import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.allocation.domain.job.AllocationJobTrackingService
import nl.stockio.allocation.domain.service.AllocationRequestTooLargeException
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.schema.AllocationRequestSchema
import nl.stockio.api.schema.AllocationRequestSchemaRegistry
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.util.backoff.FixedBackOff
import java.time.Duration
import java.util.UUID

/**
 * Configuration for Kafka allocation request processing.
 * This configuration sets up the necessary beans and properties for handling allocation requests via Kafka.
 * It includes properties for topics, concurrency, backoff strategies, and error handling.
 */
@ConfigurationProperties("stockio.allocation.kafka")
data class AllocationKafkaProperties(
    val enabled: Boolean = true,
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
    val retryAttempts: Long = 3,
    val schemaRegistryUrl: String = "http://localhost:8081",
) {
    init {
        require(topic.isNotBlank() && groupId.isNotBlank() && deadLetterTopic.isNotBlank()) {
            "Kafka allocation topic, group ID, and dead-letter topic must not be blank."
        }
        require(topic != deadLetterTopic) { "Kafka allocation and dead-letter topics must differ." }
        require(schemaRegistryUrl.isNotBlank()) { "Kafka schema registry URL must not be blank." }
        require(partitionCount > 0 && listenerConcurrency > 0 && workerCount > 0 && channelCapacity > 0) {
            "Kafka allocation partitions, concurrency, workers, and channel capacity must be positive."
        }
        require(!enabled || listenerConcurrency <= partitionCount) {
            "Kafka allocation listener concurrency cannot exceed the topic partition count."
        }
        require(
            capacityBackoffMin.toMillis() > 0 &&
                capacityBackoffMax >= capacityBackoffMin,
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
    fun allocationKafkaAdmin(kafkaProperties: KafkaProperties): KafkaAdmin = KafkaAdmin(kafkaProperties.buildAdminProperties(null))

    @Bean
    @ConditionalOnProperty(
        prefix = "stockio.allocation.kafka",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun allocationSchemaRegistration(properties: AllocationKafkaProperties): ApplicationRunner =
        ApplicationRunner {
            AllocationRequestSchemaRegistry(properties.schemaRegistryUrl).register(
                AllocationRequestSchema.subject(properties.topic),
                AllocationRequestSchema.subject(properties.deadLetterTopic),
            )
        }

    @Bean
    fun allocationKafkaListenerContainerFactory(
        kafkaProperties: KafkaProperties,
        objectMapper: ObjectMapper,
        properties: AllocationKafkaProperties,
        errorHandler: DefaultErrorHandler,
    ): ConcurrentKafkaListenerContainerFactory<String, AllocationRequest> {
        val consumerProperties = HashMap<String, Any>(kafkaProperties.buildConsumerProperties(null))
        consumerProperties.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)
        consumerProperties.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)
        consumerProperties.putAll(allocationSchemaConsumerConfig(properties.schemaRegistryUrl))
        val consumerFactory =
            DefaultKafkaConsumerFactory(
                consumerProperties,
                StringDeserializer(),
                ErrorHandlingDeserializer(AllocationJsonSchemaDeserializer(objectMapper)),
            )
        return ConcurrentKafkaListenerContainerFactory<String, AllocationRequest>().apply {
            this.consumerFactory = consumerFactory
            setCommonErrorHandler(errorHandler)
        }
    }

    @Bean
    fun allocationKafkaTemplate(
        kafkaProperties: KafkaProperties,
        objectMapper: ObjectMapper,
        properties: AllocationKafkaProperties,
    ): KafkaTemplate<String, AllocationRequest> {
        val producerProperties = HashMap<String, Any>(kafkaProperties.buildProducerProperties(null))
        producerProperties.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        producerProperties.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        producerProperties.putAll(allocationSchemaProducerConfig(properties.schemaRegistryUrl))
        return KafkaTemplate(
            DefaultKafkaProducerFactory(
                producerProperties,
                StringSerializer(),
                AllocationJsonSchemaSerializer<AllocationRequest>(objectMapper),
            ),
        )
    }

    /**
     * Publishes a successfully read AllocationRequest back through the schema serializer.
     * A deserialization failure is forwarded as the original bytes, so an invalid payload is not
     * forced through the schema again.
     */
    @Bean
    fun allocationKafkaErrorHandler(
        template: KafkaTemplate<String, AllocationRequest>,
        kafkaProperties: KafkaProperties,
        properties: AllocationKafkaProperties,
        jobTracking: AllocationJobTrackingService,
    ): DefaultErrorHandler {
        val byteProperties = HashMap<String, Any>(kafkaProperties.buildProducerProperties(null))
        byteProperties.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        byteProperties.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        val bytesTemplate =
            KafkaTemplate(
                DefaultKafkaProducerFactory<String, ByteArray>(
                    byteProperties,
                    StringSerializer(),
                    ByteArraySerializer(),
                ),
            )
        val templates: Map<Class<*>, KafkaOperations<*, *>> =
            linkedMapOf(
                AllocationRequest::class.java to template,
                ByteArray::class.java to bytesTemplate,
            )
        val deadLetterPublisher =
            DeadLetterPublishingRecoverer(templates) { record, _ ->
                TopicPartition(properties.deadLetterTopic, record.partition())
            }.also { it.setFailIfSendResultIsError(true) }
        val recoverer =
            ConsumerRecordRecoverer { record, exception ->
                deadLetterPublisher.accept(record, exception)
                val jobId =
                    try {
                        record.key()?.toString()?.let(UUID::fromString)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                if (jobId != null) jobTracking.markFailed(jobId)
            }
        return DefaultErrorHandler(
            recoverer,
            FixedBackOff(properties.retryBackoff.toMillis(), properties.retryAttempts),
        ).also {
            it.addNotRetryableExceptions(
                AllocationRequestTooLargeException::class.java,
                IllegalArgumentException::class.java,
                SerializationException::class.java,
                DeserializationException::class.java,
            )
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "stockio.allocation.kafka", name = ["enabled"], havingValue = "true")
    fun allocationKafkaTopics(properties: AllocationKafkaProperties): KafkaAdmin.NewTopics =
        KafkaAdmin.NewTopics(
            topic(properties.topic, properties.partitionCount),
            topic(properties.deadLetterTopic, properties.partitionCount),
        )

    private fun topic(
        name: String,
        partitions: Int,
    ): NewTopic =
        TopicBuilder
            .name(name)
            .partitions(partitions)
            .replicas(3)
            .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
            .build()
}

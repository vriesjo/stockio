package nl.stockio.harness.publisher

import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.schema.AllocationRequestSchema
import nl.stockio.api.schema.AllocationRequestSchemaRegistry
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

@Configuration
class AllocationKafkaPublisherConfiguration(
    private val kafkaProperties: KafkaProperties,
    private val objectMapper: ObjectMapper,
    @param:Value("\${stockio.allocation.kafka.schema-registry-url:http://localhost:8081}")
    private val schemaRegistryUrl: String,
    @param:Value("\${stockio.allocation.kafka.topic:stockio.allocation.requests}")
    private val topic: String
) {

    @Bean
    fun allocationKafkaTemplate(): KafkaTemplate<String, AllocationRequest> {
        val producerProperties = HashMap<String, Any>(kafkaProperties.buildProducerProperties(null))
        producerProperties.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)
        producerProperties.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)
        producerProperties.putAll(allocationSchemaProducerConfig(schemaRegistryUrl))
        return KafkaTemplate(
            DefaultKafkaProducerFactory(
                producerProperties,
                StringSerializer(),
                AllocationJsonSchemaSerializer<AllocationRequest>(objectMapper)
            )
        )
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "stockio.allocation.kafka.publisher",
        name = ["enabled"],
        havingValue = "true"
    )
    fun allocationSchemaRegistration(): ApplicationRunner = ApplicationRunner {
        AllocationRequestSchemaRegistry(schemaRegistryUrl)
            .register(AllocationRequestSchema.subject(topic))
    }
}

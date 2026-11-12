package nl.stockio.allocation.domain.job.listener

import com.fasterxml.jackson.databind.ObjectMapper
import io.confluent.kafka.serializers.json.KafkaJsonSchemaDeserializer
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer
import nl.stockio.api.dto.AllocationRequest

internal fun allocationSchemaProducerConfig(schemaRegistryUrl: String): Map<String, Any> = mapOf(
    "schema.registry.url" to schemaRegistryUrl,
    "auto.register.schemas" to false,
    "use.latest.version" to true,
    "latest.compatibility.strict" to false,
    "json.fail.invalid.schema" to true
)

internal fun allocationSchemaConsumerConfig(schemaRegistryUrl: String): Map<String, Any> =
    allocationSchemaProducerConfig(schemaRegistryUrl) + mapOf(
        "json.value.type" to AllocationRequest::class.java.name
    )

class AllocationJsonSchemaSerializer<T>(private val jsonMapper: ObjectMapper) : KafkaJsonSchemaSerializer<T>() {
    override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {
        super.configure(configs, isKey)
        objectMapper = jsonMapper
    }
}

class AllocationJsonSchemaDeserializer(
    private val jsonMapper: ObjectMapper
) : KafkaJsonSchemaDeserializer<AllocationRequest>() {
    override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {
        super.configure(configs, isKey)
        objectMapper = jsonMapper
    }
}

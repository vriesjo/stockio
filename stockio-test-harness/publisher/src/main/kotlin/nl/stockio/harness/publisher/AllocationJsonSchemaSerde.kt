package nl.stockio.harness.publisher

import com.fasterxml.jackson.databind.ObjectMapper
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer

internal fun allocationSchemaProducerConfig(schemaRegistryUrl: String): Map<String, Any> = mapOf(
    "schema.registry.url" to schemaRegistryUrl,
    "auto.register.schemas" to false,
    "use.latest.version" to true,
    "latest.compatibility.strict" to false,
    "json.fail.invalid.schema" to true
)

class AllocationJsonSchemaSerializer<T>(private val jsonMapper: ObjectMapper) : KafkaJsonSchemaSerializer<T>() {
    override fun configure(configs: MutableMap<String, *>?, isKey: Boolean) {
        super.configure(configs, isKey)
        objectMapper = jsonMapper
    }
}

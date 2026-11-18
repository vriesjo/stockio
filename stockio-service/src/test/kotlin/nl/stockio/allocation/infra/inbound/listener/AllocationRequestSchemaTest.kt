package nl.stockio.allocation.infra.inbound.listener

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.schemaregistry.json.JsonSchema
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.schema.AllocationRequestSchema
import nl.stockio.api.schema.AllocationRequestSchemaRegistry
import nl.stockio.allocation.domain.job.listener.AllocationJsonSchemaDeserializer
import nl.stockio.allocation.domain.job.listener.AllocationJsonSchemaSerializer
import org.apache.kafka.common.errors.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AllocationRequestSchemaTest {

    private val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `startup registration sets FULL compatibility for the request and dead-letter subjects`() {
        val seen = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            seen += "${exchange.requestMethod} ${exchange.requestURI.path} $body"
            val response = """{"id":7}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            AllocationRequestSchemaRegistry("http://127.0.0.1:${server.address.port}").register(
                "stockio.allocation.requests-value",
                "stockio.allocation.requests.DLT-value"
            )
        } finally {
            server.stop(0)
        }

        assertTrue(seen.any { it.startsWith("PUT /config/stockio.allocation.requests-value") && it.contains("FULL") })
        assertTrue(seen.any { it.startsWith("PUT /config/stockio.allocation.requests.DLT-value") && it.contains("FULL") })
        assertTrue(seen.any { it.startsWith("POST /subjects/stockio.allocation.requests-value/versions") && it.contains("JSON") })
        assertTrue(seen.any { it.contains("additionalProperties") && it.contains("requestId") })
        assertTrue(seen.any { it.startsWith("POST /subjects/stockio.allocation.requests.DLT-value/versions") })
    }

    @Test
    fun `an incompatible schema fails registration`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            val response = """{"error_code":409,"message":"incompatible"}""".toByteArray()
            exchange.sendResponseHeaders(409, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                AllocationRequestSchemaRegistry("http://127.0.0.1:${server.address.port}")
                    .register("stockio.allocation.requests-value")
            }
            assertTrue(failure.message!!.contains("409"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a valid publish stores a schema id and an unknown field is rejected`() {
        val scope = "allocation-schema-unit"
        val topic = "stockio.allocation.requests"
        val config = hashMapOf<String, Any>(
            "schema.registry.url" to "mock://$scope",
            "auto.register.schemas" to false,
            "use.latest.version" to true,
            "latest.compatibility.strict" to false,
            "json.fail.invalid.schema" to true,
            "json.value.type" to AllocationRequest::class.java.name
        )
        val serializer = AllocationJsonSchemaSerializer<AllocationRequest>(mapper)
        serializer.configure(config, false)
        val client = MockSchemaRegistry.getClientForScope(scope)
        val schemaId = client.register(
            AllocationRequestSchema.subject(topic),
            JsonSchema(AllocationRequestSchema.text())
        )
        val request = AllocationRequest(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            LocalDate.parse("2026-11-12"),
            AllocationRunType.NEW_DEMAND
        )

        val bytes = serializer.serialize(topic, request)

        assertEquals(0, bytes[0].toInt())
        assertEquals(schemaId, ByteBuffer.wrap(bytes, 1, 4).int)
        val deserializer = AllocationJsonSchemaDeserializer(mapper)
        deserializer.configure(HashMap(config), false)
        assertEquals(request, deserializer.deserialize(topic, bytes))

        val body = """{"requestId":"${request.requestId}","asOfDate":"2026-11-12","runType":"NEW_DEMAND","unexpected":true}"""
            .toByteArray()
        val framed = ByteArray(5 + body.size)
        framed[0] = 0
        ByteBuffer.wrap(framed, 1, 4).putInt(schemaId)
        body.copyInto(framed, 5)
        assertThrows(SerializationException::class.java) { deserializer.deserialize(topic, framed) }
    }
}

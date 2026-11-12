package nl.stockio.api.schema

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

object AllocationRequestSchema {
    const val RESOURCE = "/schema/allocation-request.schema.json"

    fun text(): String =
        checkNotNull(AllocationRequestSchema::class.java.getResource(RESOURCE)) {
            "Allocation request JSON Schema is missing from the classpath."
        }.readText()

    fun subject(topic: String): String = "$topic-value"
}

class AllocationRequestSchemaRegistry(
    schemaRegistryUrl: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
    private val objectMapper: ObjectMapper = ObjectMapper()
) {
    private val baseUrl = schemaRegistryUrl.trimEnd('/')

    init {
        require(baseUrl.isNotBlank()) { "Schema Registry URL must not be blank." }
    }

    fun register(vararg subjects: String) {
        val schema = AllocationRequestSchema.text()
        val schemaBody = objectMapper.writeValueAsString(
            mapOf("schemaType" to "JSON", "schema" to schema)
        )
        subjects.forEach { subject ->
            val encoded = URLEncoder.encode(subject, StandardCharsets.UTF_8)
            put("/config/$encoded", """{"compatibility":"FULL"}""")
            put("/subjects/$encoded/versions", schemaBody, method = "POST")
        }
    }

    private fun put(path: String, body: String, method: String = "PUT") {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/vnd.schemaregistry.v1+json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Schema Registry request to $path was interrupted.", exception)
        } catch (exception: Exception) {
            throw IllegalStateException("Schema Registry request to $path failed.", exception)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Schema Registry rejected $path with HTTP ${response.statusCode()}: ${response.body()}"
            )
        }
    }
}

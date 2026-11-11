package nl.stockio.product.domain.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

data class TavilySearchResult(
    val title: String,
    val url: String,
    val content: String
)

fun interface TavilySearchClient {
    fun search(query: String): List<TavilySearchResult>
}

fun interface OllamaEmbeddingClient {
    fun embed(texts: List<String>): List<List<Double>>
}

class TavilyLookupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class EmbeddingClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@ConfigurationProperties("stockio.product-resolution")
data class ProductResolutionProperties(
    var tavilyApiKey: String = "",
    var tavilyBaseUrl: String = "https://api.tavily.com",
    var ollamaBaseUrl: String = "http://localhost:11434",
    var embeddingModel: String = "nomic-embed-text",
    var requestTimeout: Duration = Duration.ofSeconds(15),
    var maxResults: Int = 5,
    var maxCandidates: Int = 5,
    var minimumSimilarity: Double = 0.72,
    var minimumSimilarityGap: Double = 0.04,
    var minimumLocalSimilarity: Double = 0.90,
    var minimumLocalSimilarityGap: Double = 0.08,
    var minimumTavilyEvidenceSimilarity: Double = 0.25
)

@Configuration
@EnableConfigurationProperties(ProductResolutionProperties::class)
class ProductResolutionClientConfiguration {

    @Bean
    @Qualifier("tavilyRestClient")
    fun tavilyRestClient(
        objectMapper: ObjectMapper,
        properties: ProductResolutionProperties
    ): RestClient = blockingClient(objectMapper, properties.tavilyBaseUrl, properties.requestTimeout)

    @Bean
    @Qualifier("ollamaRestClient")
    fun ollamaRestClient(
        objectMapper: ObjectMapper,
        properties: ProductResolutionProperties
    ): RestClient = blockingClient(objectMapper, properties.ollamaBaseUrl, properties.requestTimeout)

    // Boot does not auto-configure RestClient.Builder on a reactive web application.
    private fun blockingClient(objectMapper: ObjectMapper, baseUrl: String, timeout: Duration): RestClient =
        RestClient.builder()
            .baseUrl(baseUrl)
            .messageConverters { converters ->
                converters.removeIf { it is MappingJackson2HttpMessageConverter }
                converters.add(0, MappingJackson2HttpMessageConverter(objectMapper))
            }
            .requestFactory(requestFactory(timeout))
            .build()

    private fun requestFactory(timeout: Duration) = SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(timeout)
        setReadTimeout(timeout)
    }
}

@Component
class TavilyHttpSearchClient(
    @param:Qualifier("tavilyRestClient")
    private val restClient: RestClient,
    private val properties: ProductResolutionProperties
) : TavilySearchClient {

    override fun search(query: String): List<TavilySearchResult> {
        if (properties.tavilyApiKey.isBlank()) {
            throw TavilyLookupException("Tavily API key is not configured.")
        }

        val response = try {
            restClient.post()
                .uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    TavilySearchRequest(
                        apiKey = properties.tavilyApiKey,
                        query = query,
                        maxResults = properties.maxResults
                    )
                )
                .retrieve()
                .body(TavilySearchResponse::class.java)
        } catch (exception: RestClientException) {
            throw TavilyLookupException("Tavily product search failed.", exception)
        } ?: throw TavilyLookupException("Tavily returned an empty response.")

        return response.results.mapNotNull { result ->
            val url = result.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val content = result.content?.trim().orEmpty()
            if (content.isEmpty()) return@mapNotNull null
            TavilySearchResult(
                title = result.title?.trim().orEmpty(),
                url = url,
                content = content
            )
        }
    }

    private data class TavilySearchRequest(
        @get:JsonProperty("api_key")
        val apiKey: String,
        val query: String,
        @get:JsonProperty("search_depth")
        val searchDepth: String = "basic",
        @get:JsonProperty("max_results")
        val maxResults: Int,
        @get:JsonProperty("include_answer")
        val includeAnswer: Boolean = false
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TavilySearchResponse(
        val results: List<TavilyResultDto> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TavilyResultDto(
        val title: String? = null,
        val url: String? = null,
        val content: String? = null
    )
}

@Component
class OllamaHttpEmbeddingClient(
    @param:Qualifier("ollamaRestClient")
    private val restClient: RestClient,
    private val properties: ProductResolutionProperties
) : OllamaEmbeddingClient {

    override fun embed(texts: List<String>): List<List<Double>> {
        require(texts.isNotEmpty()) { "At least one text is required for embedding." }
        val response = try {
            restClient.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(OllamaEmbedRequest(properties.embeddingModel, texts))
                .retrieve()
                .body(OllamaEmbedResponse::class.java)
        } catch (exception: RestClientException) {
            throw EmbeddingClientException("Ollama embedding request failed.", exception)
        } ?: throw EmbeddingClientException("Ollama returned an empty embedding response.")

        val embeddings = response.embeddings
        if (embeddings.size != texts.size || embeddings.any { it.isEmpty() || it.any { value -> !value.isFinite() } }) {
            throw EmbeddingClientException("Ollama returned an invalid embedding vector.")
        }
        val dimensions = embeddings.first().size
        if (embeddings.any { it.size != dimensions }) {
            throw EmbeddingClientException("Ollama returned embedding vectors with inconsistent dimensions.")
        }
        return embeddings
    }

    private data class OllamaEmbedRequest(
        val model: String,
        val input: List<String>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OllamaEmbedResponse(
        val embeddings: List<List<Double>> = emptyList()
    )
}

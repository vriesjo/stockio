package nl.stockio.product.resolution

import nl.stockio.product.domain.service.OllamaHttpEmbeddingClient
import nl.stockio.product.domain.service.ProductResolutionProperties
import nl.stockio.product.domain.service.TavilyHttpSearchClient
import nl.stockio.product.domain.service.TavilySearchResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class ProductResolutionClientsTest {

    @Test
    fun `Tavily client sends its API contract and maps source results`() {
        val builder = RestClient.builder().baseUrl("https://api.tavily.com")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://api.tavily.com/search"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """
                        {
                          "api_key": "test-key",
                          "query": "Maki Komkommer product",
                          "search_depth": "basic",
                          "max_results": 5,
                          "include_answer": false
                        }
                    """.trimIndent()
                )
            )
            .andRespond(
                withSuccess(
                    """{"results":[{"title":"Maki Komkommer","url":"https://example.test/maki","content":"Cucumber maki"}]}""",
                    MediaType.APPLICATION_JSON
                )
            )
        val client = TavilyHttpSearchClient(
            builder.build(),
            ProductResolutionProperties(tavilyApiKey = "test-key")
        )

        val result = client.search("Maki Komkommer product")

        assertEquals(
            listOf(TavilySearchResult("Maki Komkommer", "https://example.test/maki", "Cucumber maki")),
            result
        )
        server.verify()
    }

    @Test
    fun `Ollama client sends batch inputs and parses embedding vectors`() {
        val builder = RestClient.builder().baseUrl("http://localhost:11434")
        val server = MockRestServiceServer.bindTo(builder).build()
        val embedding = List(PRODUCT_VECTOR_DIMENSIONS) { 0.0 }.joinToString(",")
        server.expect(requestTo("http://localhost:11434/api/embed"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"model":"nomic-embed-text","input":["Maki Komkommer"]}"""))
            .andRespond(
                withSuccess(
                    """{"embeddings":[[$embedding]]}""",
                    MediaType.APPLICATION_JSON
                )
            )
        val client = OllamaHttpEmbeddingClient(
            builder.build(),
            ProductResolutionProperties()
        )

        val result = client.embed(listOf("Maki Komkommer"))

        assertEquals(1, result.size)
        assertEquals(PRODUCT_VECTOR_DIMENSIONS, result.single().size)
        server.verify()
    }

    companion object {
        private const val PRODUCT_VECTOR_DIMENSIONS = 768
    }
}

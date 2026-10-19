package nl.stockio

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = ["stockio.mcp.server.enabled=false"])
@AutoConfigureMockMvc
class McpApplicationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var mcpSyncClient: McpSyncClient

    @Test
    fun contextLoads() {
    }

    @Test
    fun `allocate invokes the product allocation MCP tool`() {
        Mockito.`when`(
            mcpSyncClient.callTool(Mockito.any(McpSchema.CallToolRequest::class.java))
        ).thenReturn(McpSchema.CallToolResult("allocation complete", false))

        mockMvc.perform(
            post("/api/allocate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requestId": "11111111-1111-1111-1111-111111111111",
                      "orderId": "ORDER-1",
                      "customer": "Maki Komkommer",
                      "productQuery": "Maki Komkommer",
                      "quantity": 50,
                      "priority": 1,
                      "placedDate": "2026-10-19",
                      "requiredDeliveryDate": "2026-10-21",
                      "asOfDate": "2026-10-19",
                      "allowEquivalent": true
                    }
                    """.trimIndent()
                )
        ).andExpect(status().isOk)

        val requestCaptor = ArgumentCaptor.forClass(McpSchema.CallToolRequest::class.java)
        verify(mcpSyncClient).callTool(requestCaptor.capture())
        assertEquals("allocate_product_by_name", requestCaptor.value.name())
        assertEquals(
            mapOf(
                "request" to mapOf(
                    "requestId" to "11111111-1111-1111-1111-111111111111",
                    "orderId" to "ORDER-1",
                    "customer" to "Maki Komkommer",
                    "productQuery" to "Maki Komkommer",
                    "quantity" to 50,
                    "priority" to 1,
                    "placedDate" to "2026-10-19",
                    "requiredDeliveryDate" to "2026-10-21",
                    "asOfDate" to "2026-10-19",
                    "allowEquivalent" to true
                )
            ),
            requestCaptor.value.arguments()
        )
    }

    @Test
    fun `allocate rejects invalid request quantities`() {
        mockMvc.perform(
            post("/api/allocate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requestId": "11111111-1111-1111-1111-111111111111",
                      "orderId": "ORDER-1",
                      "customer": "Maki Komkommer",
                      "productQuery": "Maki Komkommer",
                      "quantity": 0,
                      "priority": 1,
                      "placedDate": "2026-10-19",
                      "requiredDeliveryDate": "2026-10-21",
                      "asOfDate": "2026-10-19"
                    }
                    """.trimIndent()
                )
        ).andExpect(status().isBadRequest)

        Mockito.verifyNoInteractions(mcpSyncClient)
    }
}

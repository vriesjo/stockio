## Configure the Stdio Transport Bean

Configure Spring AI to launch and communicate with your MCP server process via standard input/output:

```
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class McpStdioClientConfig {

    @Bean
    fun mcpSyncClient(): McpSyncClient {
        // Define the command to start your MCP server process (e.g., node, python, or a java jar)
        val transport = StdioClientTransport(
            ProcessBuilder("java", "-jar", "path/to/your-mcp-server.jar")
        )

        val client = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(10))
            .build()

        // Initialize the connection over STDIO handshake
        client.initialize()
        return client
    }
}
```

## Invoke the Tool from Your REST Controller

Your @PostMapping("/allocate") controller can now use the McpSyncClient to call the allocate_product_by_name tool over STDIO:

```
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class AllocationController(
    private val mcpSyncClient: McpSyncClient
) {

    @PostMapping("/allocate")
    fun allocate(
        @Valid @RequestBody request: ProductAllocationRequest
    ): ResponseEntity<Any> {
        // Map your request object into the arguments map expected by the MCP tool
        val arguments = mapOf(
            "requestId" to request.requestId,
            "orderId" to request.orderId,
            "customer" to request.customer,
            "quantity" to request.quantity,
            "priority" to request.priority,
            "placedDate" to request.placedDate.toString(),
            "requiredDeliveryDate" to request.requiredDeliveryDate.toString(),
            "asOfDate" to request.asOfDate.toString(),
            "productQuery" to request.productQuery,
            "allowEquivalent" to request.allowEquivalent
        )

        // Execute the tool call over the STDIO transport
        val toolCallRequest = McpSchema.CallToolRequest("allocate_product_by_name", arguments)
        val toolCallResult = mcpSyncClient.callTool(toolCallRequest)

        return ResponseEntity.ok(toolCallResult)
    }
}
```

package nl.stockio.api

import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import jakarta.validation.Valid
import nl.stockio.api.dto.McpProductAllocationRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class McpProductAllocationController(
    private val mcpSyncClient: McpSyncClient
) {

    @PostMapping("/allocate")
    fun allocate(
        @Valid @RequestBody request: McpProductAllocationRequest
    ): ResponseEntity<McpSchema.CallToolResult> {
        val arguments = mapOf(
            "requestId" to request.requestId.toString(),
            "orderId" to request.orderId,
            "customer" to request.customer,
            "productQuery" to request.productQuery,
            "quantity" to request.quantity,
            "priority" to request.priority,
            "placedDate" to request.placedDate.toString(),
            "requiredDeliveryDate" to request.requiredDeliveryDate.toString(),
            "asOfDate" to request.asOfDate.toString(),
            "allowEquivalent" to request.allowEquivalent
        )

        val result = mcpSyncClient.callTool(
            McpSchema.CallToolRequest("allocate_product_by_name", mapOf("request" to arguments))
        )
        return ResponseEntity.ok(result)
    }
}

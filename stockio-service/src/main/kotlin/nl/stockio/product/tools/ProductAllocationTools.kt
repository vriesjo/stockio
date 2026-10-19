package nl.stockio.product.tools

import nl.stockio.product.domain.model.ProductAllocationRequest
import nl.stockio.product.domain.model.ProductAllocationResponse
import nl.stockio.product.domain.service.ProductAllocationService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
class ProductAllocationTools(
    private val productAllocationService: ProductAllocationService
) {

    @Tool(
        name = "allocate_product_by_name",
        description = "Resolve a requested product name or description through a required Tavily lookup, " +
            "match it to an active Stockio catalog SKU, then allocate using Stockio's existing allocation rules. " +
            "Do not allocate if the product match is missing or ambiguous. Provide requestId, orderId, customer, " +
            "quantity, priority, placedDate, requiredDeliveryDate, asOfDate, and productQuery. " +
            "Equivalent products may be selected when allowEquivalent is true. Shortages follow Stockio's " +
            "partial-allocation and backorder behavior."
    )
    fun allocateProduct(request: ProductAllocationRequest): ProductAllocationResponse =
        productAllocationService.allocate(request)
}

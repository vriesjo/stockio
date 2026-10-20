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
        description = "Resolve a requested product name or description against the active Stockio catalog and local " +
            "product knowledge first, using Tavily when local matching is insufficient or ambiguous, " +
            "then allocate using Stockio's existing allocation rules. " +
            "Do not allocate if the product match is missing or ambiguous. Provide requestId, orderId, customer, " +
            "quantity, priority, placedDate, requiredDeliveryDate, asOfDate, and productQuery. " +
            "Equivalent products may be selected when allowEquivalent is true. Shortages follow Stockio's " +
            "partial-allocation and backorder behavior. Creates one order with one stable product line; " +
            "results identify both orderId and orderLineId and distinguish processed lines from lines skipped after order expiry."
    )
    fun allocateProduct(request: ProductAllocationRequest): ProductAllocationResponse =
        productAllocationService.allocate(request)
}

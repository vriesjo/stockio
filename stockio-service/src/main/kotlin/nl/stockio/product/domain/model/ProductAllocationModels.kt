package nl.stockio.product.domain.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationRunResult

data class ProductAllocationRequest(
    val requestId: UUID,
    @field:NotBlank
    val orderId: String,
    @field:NotBlank
    val customer: String,
    @field:NotBlank
    val productQuery: String,
    @field:Positive
    val quantity: Int,
    @field:Positive
    val priority: Int,
    val placedDate: LocalDate,
    val requiredDeliveryDate: LocalDate,
    val asOfDate: LocalDate,
    val allowEquivalent: Boolean = true
)

enum class ProductAllocationStatus {
    ALLOCATION_PROCESSED,
    TAVILY_LOOKUP_FAILED,
    EMBEDDING_FAILED,
    NO_PRODUCT_MATCH,
    AMBIGUOUS_PRODUCT_MATCH
}

data class ProductAllocationResponse(
    val status: ProductAllocationStatus,
    val message: String,
    val selectedProduct: ResolvedProduct? = null,
    val candidates: List<ProductCandidate> = emptyList(),
    val sources: List<ProductSource> = emptyList(),
    val allocationRun: AllocationRunResult? = null
)

data class ResolvedProduct(
    val sku: String,
    val title: String,
    val similarity: Double,
    val equivalent: Boolean
)

data class ProductCandidate(
    val sku: String,
    val title: String,
    val similarity: Double
)

data class ProductSource(
    val title: String,
    val url: String
)

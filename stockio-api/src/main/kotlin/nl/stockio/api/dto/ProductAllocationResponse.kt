package nl.stockio.api.dto

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

enum class ProductAllocationStatus {
    ALLOCATION_PROCESSED,
    TAVILY_LOOKUP_FAILED,
    EMBEDDING_FAILED,
    NO_PRODUCT_MATCH,
    AMBIGUOUS_PRODUCT_MATCH
}

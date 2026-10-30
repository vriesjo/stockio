package nl.stockio.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.LocalDate
import java.util.UUID

data class McpProductAllocationRequest(
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

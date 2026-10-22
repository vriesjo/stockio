package nl.stockio.allocation.domain.api.dto

import java.time.Instant
import java.time.LocalDate

enum class OrderReservationStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    EXPIRED
}

data class OrderReservationResponse(
    val orderId: String,
    val lines: List<OrderReservationLine>,
    val status: OrderReservationStatus,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val confirmedAt: Instant?,
    val expiredAt: Instant?,
    val cleanupPending: Boolean
)

data class OrderReservationLine(
    val orderLineId: String,
    val sku: String,
    val requestedQuantity: Int,
    val requiredDeliveryDate: LocalDate
)

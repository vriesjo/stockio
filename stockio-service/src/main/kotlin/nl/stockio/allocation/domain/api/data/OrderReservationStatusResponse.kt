package nl.stockio.allocation.domain.api.data

import java.time.Instant
import java.time.LocalDate

enum class ReservationStatus {
    PENDING_CONFIRMATION,
    CONFIRMED,
    EXPIRED
}

data class OrderReservationStatusResponse(
    val orderId: String,
    val lines: List<OrderReservationLine>,
    val status: ReservationStatus,
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

package nl.stockio.reservation.domain.service

import java.time.Clock
import nl.stockio.allocation.domain.api.data.OrderReservationStatusResponse
import nl.stockio.allocation.domain.api.data.OrderReservationLine
import nl.stockio.allocation.domain.api.data.ReservationStatus
import nl.stockio.allocation.infra.AllocationOrderClaimJpaRepository
import nl.stockio.allocation.infra.DemandOrderLineJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Retrieves the reservation status of an order, including its lines and expiration details.
 *
 * This service queries the allocation order claim repository to find the reservation status
 * of a given order ID. It also checks if the reservation is pending confirmation and has expired.
 */
@Service
class OrderReservationService(
    private val claimRepository: AllocationOrderClaimJpaRepository,
    private val lineRepository: DemandOrderLineJpaRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun getStatus(orderId: String): OrderReservationStatusResponse? {
        val claim = claimRepository.findById(orderId).orElse(null) ?: return null
        val cleanupPending = claim.reservationStatus == ReservationStatus.PENDING_CONFIRMATION &&
            !claim.canAllocate(clock.instant())
        return OrderReservationStatusResponse(
            orderId = claim.orderId,
            lines = lineRepository.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf(orderId)).map {
                OrderReservationLine(it.orderLineId, it.sku, it.requestedQuantity, it.requiredDeliveryDate)
            },
            status = if (cleanupPending) ReservationStatus.EXPIRED else claim.reservationStatus,
            createdAt = claim.reservationCreatedAt,
            expiresAt = claim.reservationExpiresAt,
            confirmedAt = claim.confirmedAt,
            expiredAt = claim.expiredAt,
            cleanupPending = cleanupPending
        )
    }
}

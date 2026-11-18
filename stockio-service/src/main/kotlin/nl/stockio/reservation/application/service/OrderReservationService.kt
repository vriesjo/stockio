package nl.stockio.reservation.domain.service

import java.time.Clock
import nl.stockio.api.dto.OrderReservationResponse
import nl.stockio.api.dto.OrderReservationLine
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.allocation.domain.port.outbound.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.outbound.DemandOrderLineRepository
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
    private val claimRepository: AllocationOrderClaimRepository,
    private val lineRepository: DemandOrderLineRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun getOrderReservationStatus(orderId: String): OrderReservationResponse? {
        val claim = claimRepository.findById(orderId).orElse(null) ?: return null
        val cleanupPending = claim.orderReservationStatus == OrderReservationStatus.PENDING_CONFIRMATION &&
            !claim.canAllocate(clock.instant())
        return OrderReservationResponse(
            orderId = claim.orderId,
            lines = lineRepository.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf(orderId)).map {
                OrderReservationLine(it.orderLineId, it.sku, it.requestedQuantity, it.requiredDeliveryDate)
            },
            status = if (cleanupPending) OrderReservationStatus.EXPIRED else claim.orderReservationStatus,
            createdAt = claim.reservationCreatedAt,
            expiresAt = claim.reservationExpiresAt,
            confirmedAt = claim.confirmedAt,
            expiredAt = claim.expiredAt,
            cleanupPending = cleanupPending
        )
    }

}

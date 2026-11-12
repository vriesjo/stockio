package nl.stockio.reservation.domain.service

import org.springframework.stereotype.Service

/**
 * Service responsible for expiring unconfirmed allocation reservations in batches.
 *
 * Cleanup owns the atomic whole-order release and affected projection refresh.
 */
@Service
class ReservationExpiryService(
    private val cleanupService: ReservationCleanupService
) {
    fun expireBatch(): Int = cleanupService.expireDueBatch()
}

package nl.stockio.reservation.domain.service

import java.time.Clock
import java.time.LocalDate
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service responsible for expiring unconfirmed allocation reservations in batches.
 *
 * This service interacts with the ReservationCleanupService to expire due reservations
 * and triggers the StockProjectionProcessor to update stock projections accordingly.
 */
@Service
class ReservationExpiryService(
    private val cleanupService: ReservationCleanupService,
    private val stockProjectionProcessor: StockProjectionProcessor,
    private val clock: Clock
) {
    @Transactional
    fun expireBatch(): Int {
        val expiredCount = cleanupService.expireDueBatch()
        if (expiredCount > 0) {
            stockProjectionProcessor.process(LocalDate.now(clock))
        }
        return expiredCount
    }
}

package nl.stockio.reservation.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.util.UUID
import nl.stockio.allocation.domain.api.data.ReservationStatus
import nl.stockio.allocation.infra.StockProjectionLockRepository
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationOrderClaimRepository
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.allocation.infra.DemandOrderLineRepository
import nl.stockio.allocation.infra.InboundReservationRepository
import nl.stockio.allocation.infra.InboundSupplyRepository
import nl.stockio.allocation.infra.OpenBackorderRepository
import nl.stockio.allocation.infra.StockEntity
import nl.stockio.allocation.infra.StockRepository
import nl.stockio.allocation.infra.StockReservationEntity
import nl.stockio.allocation.infra.StockReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

private val logger = KotlinLogging.logger {}

/**
 * Releases one bounded batch of expired, unconfirmed order reservations.
 *
 * Requires an existing transaction and acquires the exclusive stock-projection lock before
 * selecting pending order claims whose deadline is at or before the current clock instant.
 * The lock serializes cleanup with allocation, projection rebuilding, and ledger synchronization.
 *
 * For each selected order, decreases the reserved counters on its current stock lots and
 * unreceived inbound shipments, deletes its reservation rows and remaining open backorder,
 * and marks its claim [ReservationStatus.EXPIRED]. Physical stock quantities, expected inbound
 * quantities, allocation history, and order claims are retained. Reservations already converted
 * on inbound receipt are released from stock rather than from the received shipment.
 *
 * Missing sources, mismatched SKUs, or insufficient reserved quantities fail the transaction
 * instead of silently correcting inconsistent data. Expired claims are not selected again,
 * making committed cleanup safe to retry. Cleanup statistics are logged only after commit.
 *
 * [expireDueBatch] returns the number of expired orders, or zero when none are due. This service
 * neither schedules cleanup nor rebuilds projections: callers must rebuild projections in the
 * same transaction so saved snapshots cannot reintroduce released holds.
 */
@Service
class ReservationCleanupService(
    private val stockProjectionLockRepository: StockProjectionLockRepository,
    private val claimRepository: AllocationOrderClaimRepository,
    private val lineRepository: DemandOrderLineRepository,
    private val stockRepository: StockRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository,
    private val backorderRepository: OpenBackorderRepository,
    private val properties: ReservationProperties,
    private val clock: Clock
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun expireDueBatch(): Int {
        stockProjectionLockRepository.acquire()
        val now = clock.instant()
        // Select and lock a batch of expired claims, or return early if none are due.
        val claims = claimRepository.lockExpiredBatch(now, properties.expiryBatchSize)
        if (claims.isEmpty()) return 0

        validateClaims(claims, now)

        val orderIds = claims.map { it.orderId }
        val lines = lineRepository.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(orderIds)
        val linesById = lines.associateBy { it.orderLineId }

        val stockReservations = stockReservationRepository.lockAllByOrderIdIn(orderIds)
        val inboundReservations = inboundReservationRepository.lockAllByOrderIdIn(orderIds)
        val stocks = stockRepository.lockAllBySkuIn(lines.map { it.sku }.distinct()).associateBy { it.id }
        val shipmentIds = inboundReservations.map { it.shipmentLineId }.distinct()
        val supplies = if (shipmentIds.isEmpty()) {
            emptyMap()
        } else {
            inboundSupplyRepository.lockAllByShipmentLineIdIn(shipmentIds).associateBy { it.shipmentLineId }
        }

        stockReservations.forEach { reservation ->
            val stock = validateAndGetStock(stocks, reservation, linesById)
            stock.quantityReserved = Math.subtractExact(stock.quantityReserved, reservation.quantity)
            stock.updatedAt = now
        }
        inboundReservations.forEach { reservation ->
            val supply = checkNotNull(supplies[reservation.shipmentLineId]) {
                "Reservation '${reservation.id}' references missing inbound supply."
            }
            check(supply.sku == linesById.getValue(reservation.orderLineId).sku &&
                !supply.received && supply.quantityReserved >= reservation.quantity) {
                "Reservation '${reservation.id}' cannot be released from shipment '${supply.shipmentId}'."
            }
            supply.quantityReserved = Math.subtractExact(supply.quantityReserved, reservation.quantity)
        }

        stockReservationRepository.deleteAll(stockReservations)
        inboundReservationRepository.deleteAll(inboundReservations)
        backorderRepository.deleteAllByOrderIdIn(orderIds)
        claims.forEach {
            it.reservationStatus = ReservationStatus.EXPIRED
            it.expiredAt = now
        }
        claimRepository.flush()

        val releasedStockQuantity = stockReservations.sumOf { it.quantity.toLong() }
        val releasedInboundQuantity = inboundReservations.sumOf { it.quantity.toLong() }
        val oldestOverdueDeadline = claimRepository.findOldestOverdueDeadline(now)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                logger.info {
                    "Expired allocation reservations." +
                    " expiredOrderCount=${claims.size}" +
                    " releasedStockQuantity=$releasedStockQuantity" +
                    " releasedInboundQuantity=$releasedInboundQuantity" +
                    " oldestOverdueDeadline=$oldestOverdueDeadline"
                }
            }
        })
        return claims.size
    }

    // Validates that the reservation references an existing stock lot with a matching SKU and sufficient reserved quantity.
    private fun validateAndGetStock(
        stocks: Map<UUID, StockEntity>,
        reservation: StockReservationEntity,
        linesById: Map<String, DemandOrderLineEntity>
    ): StockEntity {
        val stock = checkNotNull(stocks[reservation.stockId]) {
            "Reservation '${reservation.id}' references missing stock for its order's SKU."
        }
        check(
            stock.sku == linesById.getValue(reservation.orderLineId).sku &&
                stock.quantityReserved >= reservation.quantity
        ) {
            "Reservation '${reservation.id}' exceeds the reserved quantity on stock '${stock.id}'."
        }
        return stock
    }

    // Validates that all claims are pending and overdue, throwing an exception if any claim is not.
    private fun validateClaims(
        claims: List<AllocationOrderClaimEntity>,
        now: Instant
    ) {
        check(claims.all {
            it.reservationStatus == ReservationStatus.PENDING_CONFIRMATION && !it.canAllocate(now)
        }) { "Expiry selected an order that is not overdue and pending." }
    }

}

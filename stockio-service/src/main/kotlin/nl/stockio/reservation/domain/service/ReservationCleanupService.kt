package nl.stockio.reservation.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.OrderReservationStatus
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationDocumentLockRepository
import nl.stockio.allocation.domain.port.AllocationSkuLockRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.domain.port.OpenBackorderRepository
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.reservation.domain.port.InboundReservationRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.stock.domain.port.StockRepository
import nl.stockio.stock.domain.port.StockReservationRepository
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

private val logger = KotlinLogging.logger {}

/**
 * Releases one bounded batch of expired, unconfirmed order reservations.
 *
 * Discovers candidates without lifecycle locks, then locks sorted document identities and all
 * immutable order SKUs before rechecking the lifecycle/deadline. All holds, lifecycle updates,
 * and affected forecasts commit or roll back together.
 *
 * For each selected order, decreases the reserved counters on its current stock lots and
 * unreceived inbound shipments, deletes its reservation rows and remaining open backorder,
 * and marks its claim [OrderReservationStatus.EXPIRED]. Physical stock quantities, expected inbound
 * quantities, allocation history, and order claims are retained. Reservations already converted
 * on inbound receipt are released from stock rather than from the received shipment.
 *
 * Missing sources, mismatched SKUs, or insufficient reserved quantities fail the transaction
 * instead of silently correcting inconsistent data. Expired claims are not selected again,
 * making committed cleanup safe to retry. Cleanup statistics are logged only after commit.
 *
 * [expireDueBatch] returns the number of expired orders, or zero when none are due. This service
 * does not schedule cleanup. It rebuilds affected projections in the cleanup transaction.
 */
@Service
class ReservationCleanupService(
    private val claimRepository: AllocationOrderClaimRepository,
    private val lineRepository: DemandOrderLineRepository,
    private val stockRepository: StockRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository,
    private val backorderRepository: OpenBackorderRepository,
    private val properties: ReservationProperties,
    private val clock: Clock,
    private val documentLockRepository: AllocationDocumentLockRepository,
    private val skuLockRepository: AllocationSkuLockRepository,
    private val projectionProcessor: StockProjectionProcessor
) {
    @Transactional
    fun expireDueBatch(): Int {
        return expireOrders(claimRepository.findExpiredOrderIds(clock.instant(), properties.expiryBatchSize))
    }

    @Transactional
    fun expireOrders(candidateIds: Collection<String>): Int {
        if (candidateIds.isEmpty()) return 0
        candidateIds.distinct().map { "order:$it" }.sorted().forEach { key ->
            documentLockRepository.insertIfMissing(key)
            checkNotNull(documentLockRepository.lockByKey(key)) { "Could not lock document '$key'." }
        }
        val candidateLines = lineRepository.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(candidateIds)
        val skus = candidateLines.map { it.sku }.distinct().sorted()
        skus.forEach { sku ->
            skuLockRepository.insertIfMissing(sku)
            checkNotNull(skuLockRepository.lockBySku(sku)) { "Could not lock SKU '$sku'." }
        }
        val now = clock.instant()
        val claims = candidateIds.distinct().sorted().mapNotNull { claimRepository.lockByOrderId(it) }
            .filter { it.orderReservationStatus == OrderReservationStatus.PENDING_CONFIRMATION && !it.canAllocate(now) }
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
            it.orderReservationStatus = OrderReservationStatus.EXPIRED
            it.expiredAt = now
        }
        claimRepository.flush()
        skus.forEach { projectionProcessor.processSku(LocalDate.now(clock), it) }

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
            it.orderReservationStatus == OrderReservationStatus.PENDING_CONFIRMATION && !it.canAllocate(now)
        }) { "Expiry selected an order that is not overdue and pending." }
    }

}

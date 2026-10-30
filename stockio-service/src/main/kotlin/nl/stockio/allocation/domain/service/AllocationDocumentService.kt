package nl.stockio.allocation.domain.service

import java.time.Clock
import java.time.Instant
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.port.AllocationOrderClaimRepository
import nl.stockio.allocation.domain.port.AllocationRunRepository
import nl.stockio.allocation.domain.port.DemandOrderLineRepository
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.allocation.infra.DemandOrderLineEntity
import nl.stockio.reservation.domain.service.ReservationProperties
import nl.stockio.stock.domain.port.StockProjectionLockRepository
import nl.stockio.util.requestHash
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Registers order headers, demand lines, and inbound supply before per-SKU allocation starts.
 *
 * [initialize] runs transactionally, acquiring the exclusive stock-projection lock and locking
 * the allocation run before validating its contract version, request identity, and payload hash.
 * This serializes document registration with competing requests and other projection-lock users.
 *
 * For new-demand runs, each new order is claimed by the run and its lines are stored in request
 * order. One reservation deadline is created per order using [Clock] and the configured TTL;
 * all lines share that deadline, including time spent waiting for subsequent SKU processing.
 * Existing claims must belong to the same run and match the submitted header and ordered lines.
 * Line identifiers already owned by another order are rejected.
 *
 * Inbound supply is registered for both new-demand and backorder-fulfillment runs. The run's
 * initialization marker is committed atomically with these changes. After run validation,
 * completed or already-initialized runs are skipped, so retries neither reset reservation
 * deadlines nor reapply inbound supply amendments. Any registration failure rolls back the transaction.
 *
 * This service establishes document identity and lifecycle only; SKU workers subsequently
 * calculate allocations and persist reservations and backorders.
 */
@Service
class AllocationDocumentService(
    private val stockProjectionLockRepository: StockProjectionLockRepository,
    private val allocationRunRepository: AllocationRunRepository,
    private val orderClaimRepository: AllocationOrderClaimRepository,
    private val demandOrderLineRepository: DemandOrderLineRepository,
    private val inboundSupplyService: InboundSupplyService,
    private val properties: ReservationProperties,
    private val clock: Clock
) {
    @Transactional
    fun initialize(runId: UUID, request: AllocationRequest) {
        // Serialize the short registration phase so competing requests cannot merge document identities.
        stockProjectionLockRepository.acquire()

        // Validate the run and skip completed or already-initialized runs, so retries do not reset reservation deadlines.
        val run = checkAndLockRun(runId, request)
        if (run.completed || run.documentsInitialized) return

        // Register new-demand orders and lines, claiming them for this run and creating a reservation deadline.
        if (request.runType == AllocationRunType.NEW_DEMAND) {
            request.demandOrders.forEach { order -> handleOrderClaim(order, runId) }
        }
        inboundSupplyService.registerInboundSupply(request.inboundShipments)

        run.documentsInitialized = true
    }

    // Validate and claim a new-demand order, or verify that an existing claim matches the submitted document.
    private fun handleOrderClaim(
        order: DemandOrder,
        runId: UUID,
    ) {
        val existing = orderClaimRepository.findById(order.orderId).orElse(null)
        if (existing != null) {
            handleAlreadyExistingOrderClaim(existing, runId, order)
        } else {
            val now = clock.instant()
            handleNewClaim(order, runId, now)
        }
    }

    // Verify that an existing claim belongs to the same run and matches the submitted header and ordered lines.
    private fun handleAlreadyExistingOrderClaim(
        existing: AllocationOrderClaimEntity,
        runId: UUID,
        order: DemandOrder
    ) {
        require(existing.runId == runId) { "Order '${order.orderId}' has already been submitted." }
        val lines =
            demandOrderLineRepository.findAllByOrderIdInOrderByOrderIdAscSequenceNumberAsc(listOf(order.orderId))
        require(
            existing.customer == order.customer && existing.priority == order.priority &&
                existing.placedDate == order.placedDate &&
                lines.size == order.lines.size && lines.zip(order.lines).all { (stored, input) ->
                stored.orderLineId == input.orderLineId && stored.sku == input.sku &&
                    stored.requestedQuantity == input.requestedQuantity &&
                    stored.requiredDeliveryDate == input.requiredDeliveryDate
            }) {
            "Order '${order.orderId}' was retried with different document data."
        }
    }

    // Claim a new-demand order, creating a reservation deadline.
    private fun handleNewClaim(
        order: DemandOrder,
        runId: UUID,
        now: Instant
    ) {
        require(demandOrderLineRepository.findAllById(order.lines.map { it.orderLineId }).isEmpty()) {
            "Order line IDs already belong to another order."
        }
        orderClaimRepository.saveAndFlush(
            AllocationOrderClaimEntity(
                orderId = order.orderId, runId = runId, customer = order.customer,
                priority = order.priority, placedDate = order.placedDate,
                reservationCreatedAt = now, reservationExpiresAt = now.plus(properties.ttl)
            )
        )
        demandOrderLineRepository.saveAll(order.lines.mapIndexed { index, line ->
            DemandOrderLineEntity(
                orderLineId = line.orderLineId, orderId = order.orderId, sku = line.sku,
                requestedQuantity = line.requestedQuantity,
                requiredDeliveryDate = line.requiredDeliveryDate, sequenceNumber = index
            )
        })
    }

    // Validate and get the allocation run, ensuring it matches the request.
    private fun checkAndLockRun(
        runId: UUID,
        request: AllocationRequest
    ): AllocationRunEntity {
        val run = checkNotNull(allocationRunRepository.lockById(runId)) { "Allocation run '$runId' is missing." }
        require(
            run.contractVersion == 2 && run.requestId == request.requestId &&
                run.requestHash == requestHash(request)
        ) {
            "Document initialization does not match allocation run '$runId'."
        }
        return run
    }
}

fun AllocationRequest.allocationLines(): List<AllocationOrder> =
    demandOrders.flatMap { order ->
        order.lines.map { line ->
            AllocationOrder(
                orderId = order.orderId, orderLineId = line.orderLineId, customer = order.customer,
                sku = line.sku, quantity = line.requestedQuantity, priority = order.priority,
                placedDate = order.placedDate, requiredDeliveryDate = line.requiredDeliveryDate
            )
        }
    }

package nl.stockio.allocation.domain.processor

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.allocator.AllocationCalculator
import nl.stockio.allocation.domain.allocator.InboundSupplyAllocator
import nl.stockio.allocation.domain.allocator.InventoryLotAllocator
import nl.stockio.allocation.domain.converter.toInboundReservationChange
import nl.stockio.allocation.domain.converter.toInboundSupplyState
import nl.stockio.allocation.domain.converter.toInventoryLotState
import nl.stockio.allocation.domain.converter.toStockReservationChange
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.performance.AllocationLockContentionPolicy
import nl.stockio.allocation.domain.port.SkuAllocationDatabase
import nl.stockio.allocation.infra.OpenBackorderEntity
import nl.stockio.api.dto.AllocationProcessingOutcome
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationStatus
import nl.stockio.stock.infra.StockEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Allocates one SKU inside a single R2DBC transaction.
 *
 * Lock and statement timeouts are applied on that transaction's connection before the SKU lock is taken.
 * Registration, projection, and completion stay on JDBC and are not enlisted here.
 */
@Service
class SkuAllocationProcessor(
    private val database: SkuAllocationDatabase,
    private val transactionalOperator: TransactionalOperator,
    private val lockContentionPolicy: AllocationLockContentionPolicy,
    private val clock: Clock,
    private val objectMapper: ObjectMapper
) {

    private val allocationCalculator = AllocationCalculator(
        InventoryLotAllocator(),
        InboundSupplyAllocator()
    )

    fun process(
        runId: UUID,
        requestHash: String,
        sku: String,
        asOfDate: LocalDate,
        orders: List<AllocationOrder>
    ): Mono<List<AllocationRecord>> = inTransaction {
        database.lockSku(sku).then(Mono.defer {
            cachedOrCalculated(runId, sku, requestHash) {
                allocateDemand(runId, requestHash, sku, asOfDate, orders)
            }
        })
    }

    fun allocateBackorders(
        runId: UUID,
        requestHash: String,
        sku: String,
        asOfDate: LocalDate
    ): Mono<List<AllocationRecord>> = inTransaction {
        database.lockSku(sku).then(Mono.defer {
            cachedOrCalculated(runId, sku, requestHash) {
                allocateOpenBackorders(runId, requestHash, sku, asOfDate)
            }
        })
    }

    private fun inTransaction(work: () -> Mono<List<AllocationRecord>>): Mono<List<AllocationRecord>> =
        transactionalOperator.transactional(
            Mono.defer {
                lockContentionPolicy.applyReactive().then(Mono.defer(work))
            }
        )

    private fun cachedOrCalculated(
        runId: UUID,
        sku: String,
        requestHash: String,
        calculate: () -> Mono<List<AllocationRecord>>
    ): Mono<List<AllocationRecord>> =
        database.findOperation(runId, sku).flatMap { operation ->
            if (operation.requestHash != requestHash) {
                Mono.error(IllegalArgumentException(
                    "Allocation request was retried with different data for SKU '$sku'."
                ))
            } else {
                Mono.just(objectMapper.readValue(operation.resultJson, allocationRecordListType))
            }
        }.switchIfEmpty(Mono.defer {
            database.completedAllocations(runId, sku).switchIfEmpty(Mono.defer(calculate))
        })

    private fun allocateDemand(
        runId: UUID,
        requestHash: String,
        sku: String,
        asOfDate: LocalDate,
        orders: List<AllocationOrder>
    ): Mono<List<AllocationRecord>> =
        database.findClaims(orders.map { it.orderId }).flatMap { claims ->
            database.findLines(orders.map { it.orderLineId }).flatMap { lines ->
                val claimById = claims.associateBy { it.orderId }
                val lineById = lines.associateBy { it.orderLineId }
                val now = clock.instant()
                val (eligible, expired) = orders.partition { order ->
                    val claim = checkNotNull(claimById[order.orderId]) {
                        "Order '${order.orderId}' was not initialized."
                    }
                    val line = checkNotNull(lineById[order.orderLineId]) {
                        "Order line '${order.orderLineId}' was not initialized."
                    }
                    require(claim.runId == runId) { "Order '${order.orderId}' belongs to another run." }
                    require(
                        line.orderId == order.orderId && line.sku == sku && order.sku == sku &&
                            line.requestedQuantity == order.quantity &&
                            line.requiredDeliveryDate == order.requiredDeliveryDate &&
                            claim.customer == order.customer && claim.priority == order.priority &&
                            claim.placedDate == order.placedDate
                    ) {
                        "Order line '${order.orderLineId}' does not match its registered document."
                    }
                    claim.canAllocate(now)
                }
                database.lockUnreceivedSupply(sku).flatMap { supply ->
                    database.lockStock(sku).flatMap { stock ->
                        val lineIds = eligible.map { it.orderLineId }
                        database.lockStockReservations(lineIds).flatMap { stockReservations ->
                            database.lockInboundReservations(lineIds).map { inboundReservations ->
                                val supplyById = supply.associateBy { it.shipmentLineId }
                                val stockBySku = groupStock(stock, asOfDate)
                                val calculation = allocationCalculator.calculate(
                                    orders = eligible,
                                    asOfDate = asOfDate,
                                    inventory = stockBySku.values.flatten().map(::toInventoryLotState),
                                    inboundSupply = supplyById.values.map(::toInboundSupplyState),
                                    stockReservations = stockReservations.map(::toStockReservationChange),
                                    inboundReservations = inboundReservations.map(::toInboundReservationChange),
                                    registerBackorders = true
                                )
                                val skipped = expired.map { order ->
                                    AllocationRecord(
                                        orderId = order.orderId,
                                        orderLineId = order.orderLineId,
                                        customer = order.customer,
                                        sku = order.sku,
                                        priority = order.priority,
                                        placedDate = order.placedDate,
                                        requiredDeliveryDate = order.requiredDeliveryDate,
                                        requestedQuantity = order.quantity,
                                        allocatedFromInventory = 0,
                                        allocatedFromInbound = 0,
                                        backorderedQuantity = 0,
                                        status = null,
                                        sources = emptyList(),
                                        processingOutcome = AllocationProcessingOutcome.SKIPPED_ORDER_EXPIRED
                                    )
                                }
                                calculation to (calculation.allocations + skipped)
                            }
                        }
                    }
                }.flatMap { (calculation, allocations) ->
                    database.saveCalculation(calculation, Instant.now()).then(
                        persist(runId, requestHash, sku, allocations)
                    )
                }
            }
        }

    private fun allocateOpenBackorders(
        runId: UUID,
        requestHash: String,
        sku: String,
        asOfDate: LocalDate
    ): Mono<List<AllocationRecord>> =
        database.lockBackorders(sku).flatMap { backorders ->
            database.findClaims(backorders.map { it.orderId }).flatMap { claims ->
                val claimById = claims.associateBy { it.orderId }
                val now = clock.instant()
                val pending = backorders.filter { backorder ->
                    val claim = checkNotNull(claimById[backorder.orderId]) {
                        "Backorder '${backorder.orderId}' has no allocation order claim."
                    }
                    claim.canAllocate(now)
                }
                database.lockUnreceivedSupply(sku).flatMap { supply ->
                    database.lockStock(sku).flatMap { stock ->
                        val lineIds = pending.map { it.orderLineId }
                        database.lockStockReservations(lineIds).flatMap { stockReservations ->
                            database.lockInboundReservations(lineIds).map { inboundReservations ->
                                val eligibleSupply = supply.filter { it.expectedArrivalDate <= asOfDate }
                                val calculation = allocationCalculator.calculate(
                                    orders = pending.map {
                                        AllocationOrder(
                                            orderId = it.orderId,
                                            orderLineId = it.orderLineId,
                                            customer = it.customer,
                                            sku = it.sku,
                                            quantity = it.remainingQuantity,
                                            priority = it.priority,
                                            placedDate = it.placedDate,
                                            requiredDeliveryDate = it.requiredDeliveryDate
                                        )
                                    },
                                    asOfDate = asOfDate,
                                    inventory = groupStock(stock, asOfDate).values.flatten().map(::toInventoryLotState),
                                    inboundSupply = eligibleSupply.map(::toInboundSupplyState),
                                    stockReservations = stockReservations.map(::toStockReservationChange),
                                    inboundReservations = inboundReservations.map(::toInboundReservationChange),
                                    registerBackorders = false
                                )
                                val backordersByLineId = pending.associateBy { it.orderLineId }
                                val allocations = calculation.allocations.map { allocation ->
                                    val backorder = backordersByLineId.getValue(allocation.orderLineId)
                                    val allocated = allocation.allocatedFromInventory + allocation.allocatedFromInbound
                                    allocation.copy(
                                        status = when {
                                            allocation.backorderedQuantity == 0 -> AllocationStatus.FULFILLED
                                            allocated > 0 -> AllocationStatus.PARTIALLY_FULFILLED
                                            else -> AllocationStatus.BACKORDERED
                                        }
                                    ) to backorder
                                }
                                calculation to allocations
                            }
                        }
                    }
                }.flatMap { (calculation, allocations) ->
                    Flux.fromIterable(allocations).concatMap { (allocation, backorder) ->
                        updateBackorder(allocation, backorder)
                    }.then(database.saveCalculation(calculation, Instant.now())).then(
                        persist(runId, requestHash, sku, allocations.map { it.first })
                    )
                }
            }
        }

    private fun updateBackorder(
        allocation: AllocationRecord,
        backorder: OpenBackorderEntity
    ): Mono<Void> =
        if (allocation.backorderedQuantity == 0) {
            database.deleteBackorder(backorder.orderLineId)
        } else {
            database.updateBackorderQuantity(backorder.orderLineId, allocation.backorderedQuantity, Instant.now())
        }

    private fun persist(
        runId: UUID,
        requestHash: String,
        sku: String,
        allocations: List<AllocationRecord>
    ): Mono<List<AllocationRecord>> =
        database.saveOperation(runId, sku, requestHash, objectMapper.writeValueAsString(allocations))
            .thenReturn(allocations)

    private fun groupStock(stock: List<StockEntity>, asOfDate: LocalDate): Map<String, List<StockEntity>> =
        stock.filter { it.receivedDate <= asOfDate }
            .groupBy { it.sku }
            .mapValues { (_, lots) ->
                lots.sortedWith(
                    compareBy<StockEntity> { it.receivedDate }.thenBy { it.lotId }.thenBy { it.warehouseCode }
                )
            }

    private companion object {
        val allocationRecordListType = object : TypeReference<List<AllocationRecord>>() {}
    }
}

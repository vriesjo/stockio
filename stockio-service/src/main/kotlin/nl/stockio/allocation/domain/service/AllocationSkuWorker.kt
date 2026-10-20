package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationRecord
import nl.stockio.allocation.domain.api.data.AllocationStatus
import nl.stockio.allocation.domain.api.data.AllocationProcessingOutcome
import nl.stockio.allocation.domain.converter.toInboundReservationChange
import nl.stockio.allocation.domain.converter.toInboundSupplyState
import nl.stockio.allocation.domain.converter.toInventoryLotState
import nl.stockio.allocation.domain.converter.toStockReservationChange
import nl.stockio.allocation.domain.model.AllocationCalculationResult
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.policy.AllocationPolicyCalculator
import nl.stockio.allocation.domain.policy.InboundAllocationPolicy
import nl.stockio.allocation.domain.policy.InventoryAllocationPolicy
import nl.stockio.stock.infra.StockProjectionLockJpaRepository
import nl.stockio.allocation.infra.AllocationOrderClaimJpaRepository
import nl.stockio.allocation.infra.AllocationSkuLockJpaRepository
import nl.stockio.allocation.infra.AllocationSkuOperationEntity
import nl.stockio.allocation.infra.AllocationSkuOperationJpaRepository
import nl.stockio.allocation.infra.DemandOrderLineJpaRepository
import nl.stockio.allocation.infra.OpenBackorderEntity
import nl.stockio.allocation.infra.OpenBackorderJpaRepository
import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockJpaRepository
import nl.stockio.stock.infra.StockReservationEntity
import nl.stockio.stock.infra.StockReservationJpaRepository
import nl.stockio.reservation.infra.InboundReservationJpaRepository
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.reservation.infra.InboundSupplyJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationSkuWorker(
    private val stockProjectionLockRepository: StockProjectionLockJpaRepository,
    private val allocationRunService: AllocationRunService,
    private val skuLockRepository: AllocationSkuLockJpaRepository,
    private val skuOperationRepository: AllocationSkuOperationJpaRepository,
    private val orderClaimRepository: AllocationOrderClaimJpaRepository,
    private val lineRepository: DemandOrderLineJpaRepository,
    private val inboundSupplyRepository: InboundSupplyJpaRepository,
    private val stockRepository: StockJpaRepository,
    private val stockReservationRepository: StockReservationJpaRepository,
    private val inboundReservationRepository: InboundReservationJpaRepository,
    private val backorderRepository: OpenBackorderJpaRepository,
    private val clock: Clock,
    private val objectMapper: ObjectMapper
) {

    private val allocationPolicyCalculator = AllocationPolicyCalculator(
        InventoryAllocationPolicy(),
        InboundAllocationPolicy()
    )

    @Transactional
    fun allocateNewDemand(
        runId: UUID,
        requestHash: String,
        sku: String,
        asOfDate: LocalDate,
        orders: List<AllocationOrder>
    ): List<AllocationRecord> {
        lockSku(sku)
        existingResult(runId, sku, requestHash)?.let { return it }
        allocationRunService.getRunById(runId)?.let { run ->
            return run.allocations.filter { it.sku == sku }
        }

        val claims = orderClaimRepository.findAllById(orders.map { it.orderId }.distinct()).associateBy { it.orderId }
        val storedLines = lineRepository.findAllById(orders.map { it.orderLineId }).associateBy { it.orderLineId }
        val now = clock.instant()
        val (eligible, expired) = orders.partition { order ->
            val claim = checkNotNull(claims[order.orderId]) { "Order '${order.orderId}' was not initialized." }
            val line = checkNotNull(storedLines[order.orderLineId]) {
                "Order line '${order.orderLineId}' was not initialized."
            }
            require(claim.runId == runId) { "Order '${order.orderId}' belongs to another run." }
            require(line.orderId == order.orderId && line.sku == sku && order.sku == sku &&
                line.requestedQuantity == order.quantity && line.requiredDeliveryDate == order.requiredDeliveryDate &&
                claim.customer == order.customer && claim.priority == order.priority && claim.placedDate == order.placedDate) {
                "Order line '${order.orderLineId}' does not match its registered document."
            }
            claim.canAllocate(now)
        }
        val lineIds = eligible.map { it.orderLineId }

        val inboundSupplyById = lockUnreceivedSupply(sku)
        val stockById = lockStock(sku, asOfDate)
        val calculation = allocationPolicyCalculator.calculate(
            orders = eligible,
            asOfDate = asOfDate,
            inventory = stockById.values.flatten().map(::toInventoryLotState),
            inboundSupply = inboundSupplyById.values.map(::toInboundSupplyState),
            stockReservations = loadStockReservations(lineIds).map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(lineIds).map(::toInboundReservationChange),
            registerBackorders = true
        )
        saveCalculationChanges(calculation, stockById, inboundSupplyById)
        val skipped = expired.map { order ->
            AllocationRecord(
                orderId = order.orderId, orderLineId = order.orderLineId, customer = order.customer,
                sku = order.sku, priority = order.priority, placedDate = order.placedDate,
                requiredDeliveryDate = order.requiredDeliveryDate, requestedQuantity = order.quantity,
                allocatedFromInventory = 0, allocatedFromInbound = 0, backorderedQuantity = 0,
                status = null, sources = emptyList(),
                processingOutcome = AllocationProcessingOutcome.SKIPPED_ORDER_EXPIRED
            )
        }
        return persistResult(runId, requestHash, sku, calculation.allocations + skipped)
    }

    @Transactional
    fun fulfillBackorders(
        runId: UUID,
        requestHash: String,
        sku: String,
        asOfDate: LocalDate
    ): List<AllocationRecord> {
        lockSku(sku)
        existingResult(runId, sku, requestHash)?.let { return it }
        allocationRunService.getRunById(runId)?.let { run ->
            return run.allocations.filter { it.sku == sku }
        }

        val backorders = backorderRepository.lockAllBySkuOrderByPriorityFifo(sku)
        val claims = orderClaimRepository.findAllById(backorders.map { it.orderId }).associateBy { it.orderId }
        val now = clock.instant()
        val pendingBackorders = backorders.filter { backorder ->
            val claim = checkNotNull(claims[backorder.orderId]) {
                "Backorder '${backorder.orderId}' has no allocation order claim."
            }
            claim.canAllocate(now)
        }
        val inboundSupplyById = lockUnreceivedSupply(sku)
        val stockById = lockStock(sku, asOfDate)
        val eligibleSupply = inboundSupplyById.values.filter { it.expectedArrivalDate <= asOfDate }
        val lineIds = pendingBackorders.map { it.orderLineId }
        val calculation = allocationPolicyCalculator.calculate(
            orders = pendingBackorders.map {
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
            inventory = stockById.values.flatten().map(::toInventoryLotState),
            inboundSupply = eligibleSupply.map(::toInboundSupplyState),
            stockReservations = loadStockReservations(lineIds).map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(lineIds).map(::toInboundReservationChange),
            registerBackorders = false
        )
        val backordersByLineId = pendingBackorders.associateBy { it.orderLineId }
        val allocations = calculation.allocations.map { allocation ->
            val backorder = backordersByLineId.getValue(allocation.orderLineId)
            val allocated = allocation.allocatedFromInventory + allocation.allocatedFromInbound
            if (allocation.backorderedQuantity == 0) {
                backorderRepository.delete(backorder)
            } else {
                backorder.remainingQuantity = allocation.backorderedQuantity
                backorder.updatedAt = Instant.now()
                backorderRepository.save(backorder)
            }
            allocation.copy(
                status = when {
                    allocation.backorderedQuantity == 0 -> AllocationStatus.FULFILLED
                    allocated > 0 -> AllocationStatus.PARTIALLY_FULFILLED
                    else -> AllocationStatus.BACKORDERED
                }
            )
        }
        saveCalculationChanges(calculation, stockById, inboundSupplyById)
        return persistResult(runId, requestHash, sku, allocations)
    }

    private fun lockSku(sku: String) {
        stockProjectionLockRepository.acquireAllocationReadLock()
        skuLockRepository.insertIfMissing(sku)
        check(skuLockRepository.lockBySku(sku) != null) {
            "Could not acquire allocation lock for SKU '$sku'."
        }
    }

    private fun existingResult(runId: UUID, sku: String, requestHash: String): List<AllocationRecord>? =
        skuOperationRepository.findByRunIdAndSku(runId, sku)?.let { operation ->
            require(operation.requestHash == requestHash) {
                "Allocation request was retried with different data for SKU '$sku'."
            }
            objectMapper.readValue(operation.resultJson, allocationRecordListType)
        }

    private fun persistResult(
        runId: UUID,
        requestHash: String,
        sku: String,
        allocations: List<AllocationRecord>
    ): List<AllocationRecord> {
        skuOperationRepository.save(
            AllocationSkuOperationEntity(
                runId = runId,
                sku = sku,
                requestHash = requestHash,
                resultJson = objectMapper.writeValueAsString(allocations)
            )
        )
        return allocations
    }

    private fun lockUnreceivedSupply(sku: String): Map<String, InboundSupplyEntity> =
        inboundSupplyRepository.lockAllUnreceivedBySku(sku).associateBy { it.shipmentLineId }

    private fun lockStock(sku: String, asOfDate: LocalDate): Map<String, List<StockEntity>> =
        stockRepository.lockAllBySkuIn(listOf(sku))
            .filter { it.receivedDate <= asOfDate }
            .groupBy { it.sku }
            .mapValues { (_, lots) ->
                lots.sortedWith(compareBy<StockEntity> { it.receivedDate }.thenBy { it.lotId }
                    .thenBy { it.warehouseCode })
            }

    private fun loadStockReservations(lineIds: Collection<String>): List<StockReservationEntity> =
        if (lineIds.isEmpty()) emptyList() else stockReservationRepository.lockAllByOrderLineIdIn(lineIds)

    private fun loadInboundReservations(lineIds: Collection<String>): List<InboundReservationEntity> =
        if (lineIds.isEmpty()) emptyList() else inboundReservationRepository.lockAllByOrderLineIdIn(lineIds)

    private fun saveCalculationChanges(
        calculation: AllocationCalculationResult,
        stockById: Map<String, List<StockEntity>>,
        inboundSupplyById: Map<String, InboundSupplyEntity>
    ) {
        val stocks = stockById.values.flatten().associateBy { it.id }
        calculation.inventoryLots.forEach { lot ->
            stocks.getValue(lot.stockId).quantityReserved = lot.quantityReserved
        }
        calculation.inboundSupplies.forEach { supply ->
            inboundSupplyById.getValue(supply.shipmentLineId).quantityReserved = supply.quantityReserved
        }
        stockReservationRepository.saveAll(
            calculation.stockReservations.map { reservation ->
                StockReservationEntity(
                    id = reservation.id ?: UUID.randomUUID(),
                    orderId = reservation.orderId,
                    orderLineId = reservation.orderLineId,
                    stockId = reservation.stockId,
                    quantity = reservation.quantity,
                    requiredDeliveryDate = reservation.requiredDeliveryDate,
                    createdAt = reservation.createdAt ?: Instant.now()
                )
            }
        )
        inboundSupplyRepository.saveAll(
            calculation.inboundSupplies.map { inboundSupplyById.getValue(it.shipmentLineId) }
        )
        inboundReservationRepository.saveAll(
            calculation.inboundReservations.map { reservation ->
                InboundReservationEntity(
                    id = reservation.id ?: UUID.randomUUID(),
                    orderId = reservation.orderId,
                    orderLineId = reservation.orderLineId,
                    shipmentId = reservation.shipmentId,
                    shipmentLineId = reservation.shipmentLineId,
                    quantity = reservation.quantity,
                    requiredDeliveryDate = reservation.requiredDeliveryDate,
                    createdAt = reservation.createdAt ?: Instant.now()
                )
            }
        )
        backorderRepository.saveAll(
            calculation.backordersToRegister.map { backorder ->
                OpenBackorderEntity(
                    orderId = backorder.orderId,
                    orderLineId = backorder.orderLineId,
                    customer = backorder.customer,
                    sku = backorder.sku,
                    priority = backorder.priority,
                    placedDate = backorder.placedDate,
                    requiredDeliveryDate = backorder.requiredDeliveryDate,
                    originalQuantity = backorder.originalQuantity,
                    remainingQuantity = backorder.quantity,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            }
        )
    }

    private companion object {
        val allocationRecordListType = object : TypeReference<List<AllocationRecord>>() {}
    }
}

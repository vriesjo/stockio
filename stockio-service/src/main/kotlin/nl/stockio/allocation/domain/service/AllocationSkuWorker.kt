package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationRecord
import nl.stockio.allocation.domain.api.data.AllocationStatus
import nl.stockio.allocation.domain.api.data.DemandOrder
import nl.stockio.allocation.domain.api.data.InboundShipment
import nl.stockio.allocation.domain.converter.toInboundReservationChange
import nl.stockio.allocation.domain.converter.toInboundSupplyState
import nl.stockio.allocation.domain.converter.toInventoryLotState
import nl.stockio.allocation.domain.converter.toStockReservationChange
import nl.stockio.allocation.domain.model.AllocationCalculationResult
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.policy.AllocationPolicyCalculator
import nl.stockio.allocation.domain.policy.InboundAllocationPolicy
import nl.stockio.allocation.domain.policy.InventoryAllocationPolicy
import nl.stockio.allocation.domain.processor.StockProjectionLockService
import nl.stockio.allocation.infra.AllocationOrderClaimEntity
import nl.stockio.allocation.infra.AllocationOrderClaimRepository
import nl.stockio.allocation.infra.AllocationSkuLockRepository
import nl.stockio.allocation.infra.AllocationSkuOperationEntity
import nl.stockio.allocation.infra.AllocationSkuOperationRepository
import nl.stockio.allocation.infra.InboundReservationEntity
import nl.stockio.allocation.infra.InboundReservationRepository
import nl.stockio.allocation.infra.InboundSupplyEntity
import nl.stockio.allocation.infra.InboundSupplyRepository
import nl.stockio.allocation.infra.OpenBackorderEntity
import nl.stockio.allocation.infra.OpenBackorderRepository
import nl.stockio.allocation.infra.StockEntity
import nl.stockio.allocation.infra.StockRepository
import nl.stockio.allocation.infra.StockReservationEntity
import nl.stockio.allocation.infra.StockReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationSkuWorker(
    private val stockProjectionLockService: StockProjectionLockService,
    private val allocationRunService: AllocationRunService,
    private val skuLockRepository: AllocationSkuLockRepository,
    private val skuOperationRepository: AllocationSkuOperationRepository,
    private val orderClaimRepository: AllocationOrderClaimRepository,
    private val inboundSupplyService: InboundSupplyService,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val stockRepository: StockRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundReservationRepository: InboundReservationRepository,
    private val backorderRepository: OpenBackorderRepository,
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
        orders: List<DemandOrder>,
        shipments: List<InboundShipment>
    ): List<AllocationRecord> {
        lockSku(sku)
        existingResult(runId, sku, requestHash)?.let { return it }
        allocationRunService.getRunById(runId)?.let { run ->
            return run.allocations.filter { it.sku == sku }
        }

        val orderIds = orders.map { it.orderId }
        val existingClaims = orderClaimRepository.findAllById(orderIds)
        require(existingClaims.isEmpty()) {
            "Orders have already been submitted: ${existingClaims.map { it.orderId }.joinToString()}."
        }
        orderClaimRepository.saveAll(orders.map {
            AllocationOrderClaimEntity(orderId = it.orderId, runId = runId, sku = sku)
        })

        inboundSupplyService.registerInboundSupply(shipments)
        val inboundSupplyById = lockUnreceivedSupply(sku)
        val stockById = lockStock(sku, asOfDate)
        val calculation = allocationPolicyCalculator.calculate(
            orders = orders.map {
                AllocationOrder(
                    orderId = it.orderId,
                    customer = it.customer,
                    sku = it.sku,
                    quantity = it.requestedQuantity,
                    priority = it.priority,
                    placedDate = it.placedDate,
                    requiredDeliveryDate = it.requiredDeliveryDate
                )
            },
            asOfDate = asOfDate,
            inventory = stockById.values.flatten().map(::toInventoryLotState),
            inboundSupply = inboundSupplyById.values.map(::toInboundSupplyState),
            stockReservations = loadStockReservations(orderIds).map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(orderIds).map(::toInboundReservationChange),
            registerBackorders = true
        )
        saveCalculationChanges(calculation, stockById, inboundSupplyById)
        return persistResult(runId, requestHash, sku, calculation.allocations)
    }

    @Transactional
    fun fulfillBackorders(
        runId: UUID,
        requestHash: String,
        sku: String,
        asOfDate: LocalDate,
        shipments: List<InboundShipment>
    ): List<AllocationRecord> {
        lockSku(sku)
        existingResult(runId, sku, requestHash)?.let { return it }
        allocationRunService.getRunById(runId)?.let { run ->
            return run.allocations.filter { it.sku == sku }
        }

        inboundSupplyService.registerInboundSupply(shipments)
        val pendingBackorders = backorderRepository.lockAllBySkuOrderByPriorityFifo(sku)
        val inboundSupplyById = lockUnreceivedSupply(sku)
        val stockById = lockStock(sku, asOfDate)
        val eligibleSupply = inboundSupplyById.values.filter { it.expectedArrivalDate <= asOfDate }
        val orderIds = pendingBackorders.map { it.orderId }
        val calculation = allocationPolicyCalculator.calculate(
            orders = pendingBackorders.map {
                AllocationOrder(
                    orderId = it.orderId,
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
            stockReservations = loadStockReservations(orderIds).map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(orderIds).map(::toInboundReservationChange),
            registerBackorders = false
        )
        val backordersByOrderId = pendingBackorders.associateBy { it.orderId }
        val allocations = calculation.allocations.map { allocation ->
            val backorder = backordersByOrderId.getValue(allocation.orderId)
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
        stockProjectionLockService.acquireAllocationReadLock()
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
        inboundSupplyRepository.lockAllUnreceivedBySku(sku).associateBy { it.shipmentId }

    private fun lockStock(sku: String, asOfDate: LocalDate): Map<String, List<StockEntity>> =
        stockRepository.lockAllBySkuIn(listOf(sku))
            .filter { it.receivedDate <= asOfDate }
            .groupBy { it.sku }
            .mapValues { (_, lots) ->
                lots.sortedWith(compareBy<StockEntity> { it.receivedDate }.thenBy { it.lotId }
                    .thenBy { it.warehouseCode })
            }

    private fun loadStockReservations(orderIds: Collection<String>): List<StockReservationEntity> =
        if (orderIds.isEmpty()) emptyList() else stockReservationRepository.lockAllByOrderIdIn(orderIds)

    private fun loadInboundReservations(orderIds: Collection<String>): List<InboundReservationEntity> =
        if (orderIds.isEmpty()) emptyList() else inboundReservationRepository.lockAllByOrderIdIn(orderIds)

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
            inboundSupplyById.getValue(supply.shipmentId).quantityReserved = supply.quantityReserved
        }
        stockReservationRepository.saveAll(
            calculation.stockReservations.map { reservation ->
                StockReservationEntity(
                    id = reservation.id ?: UUID.randomUUID(),
                    orderId = reservation.orderId,
                    stockId = reservation.stockId,
                    quantity = reservation.quantity,
                    requiredDeliveryDate = reservation.requiredDeliveryDate,
                    createdAt = reservation.createdAt ?: Instant.now()
                )
            }
        )
        inboundSupplyRepository.saveAll(
            calculation.inboundSupplies.map { inboundSupplyById.getValue(it.shipmentId) }
        )
        inboundReservationRepository.saveAll(
            calculation.inboundReservations.map { reservation ->
                InboundReservationEntity(
                    id = reservation.id ?: UUID.randomUUID(),
                    orderId = reservation.orderId,
                    shipmentId = reservation.shipmentId,
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

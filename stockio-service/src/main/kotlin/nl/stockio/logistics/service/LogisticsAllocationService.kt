package nl.stockio.logistics.service

import jakarta.validation.Validator
import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.logistics.domain.AllocationRecord
import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.domain.AllocationRunResult
import nl.stockio.logistics.domain.AllocationRunType
import nl.stockio.logistics.domain.AllocationSource
import nl.stockio.logistics.domain.AllocationStatus
import nl.stockio.logistics.domain.BackorderFulfillmentRequest
import nl.stockio.logistics.domain.DemandOrder
import nl.stockio.logistics.domain.InboundShipment
import nl.stockio.logistics.domain.allocation.AllocationCalculation
import nl.stockio.logistics.domain.allocation.AllocationCalculationResult
import nl.stockio.logistics.domain.allocation.AllocationOrder
import nl.stockio.logistics.domain.allocation.InboundAllocationPolicy
import nl.stockio.logistics.domain.allocation.InboundReservationChange
import nl.stockio.logistics.domain.allocation.InboundSupplyState
import nl.stockio.logistics.domain.allocation.InventoryAllocationPolicy
import nl.stockio.logistics.domain.allocation.InventoryLotState
import nl.stockio.logistics.domain.allocation.StockReservationChange
import nl.stockio.logistics.persistence.AllocationRecordEntity
import nl.stockio.logistics.persistence.AllocationRecordRepository
import nl.stockio.logistics.persistence.AllocationRunEntity
import nl.stockio.logistics.persistence.AllocationRunRepository
import nl.stockio.logistics.persistence.AllocationSourceEntity
import nl.stockio.logistics.persistence.AllocationSourceRepository
import nl.stockio.logistics.persistence.OpenBackorderEntity
import nl.stockio.logistics.persistence.OpenBackorderRepository
import nl.stockio.logistics.persistence.InboundReservationEntity
import nl.stockio.logistics.persistence.InboundReservationRepository
import nl.stockio.logistics.persistence.InboundSupplyEntity
import nl.stockio.logistics.persistence.InboundSupplyRepository
import nl.stockio.logistics.persistence.StockEntity
import nl.stockio.logistics.persistence.StockRepository
import nl.stockio.logistics.persistence.StockReservationEntity
import nl.stockio.logistics.persistence.StockReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class LogisticsAllocationService(
    private val validator: Validator,
    private val objectMapper: ObjectMapper,
    private val runRepository: AllocationRunRepository,
    private val recordRepository: AllocationRecordRepository,
    private val sourceRepository: AllocationSourceRepository,
    private val backorderRepository: OpenBackorderRepository,
    private val stockRepository: StockRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository
) {

    private val allocationCalculation = AllocationCalculation(
        InventoryAllocationPolicy(),
        InboundAllocationPolicy()
    )

    // Allocation process for new demand orders
    // This method validates the request, checks for duplicate order IDs, and ensures that the orders have not been processed before.
    // It registers inbound supply, loads and locks resources, then calculates and persists the allocation.
    // If requested multiple times with the same requestId and identical request content,
    // the method will return the previous allocation run result instead of recalculating.
    @Transactional
    fun calculateAllocation(request: AllocationRequest): AllocationRunResult {
        validate(request)
        validateSources(request.inboundShipments)
        val requestHash = requestHash(request)
        runRepository.findByRequestId(request.requestId)?.let { previousRun ->
            require(previousRun.runType == AllocationRunType.NEW_DEMAND && previousRun.requestHash == requestHash) {
                "requestId '${request.requestId}' has already been used with a different request."
            }
            return getRun(previousRun.id)
        }

        val orderIds = request.demandOrders.map { it.orderId }
        require(orderIds.distinct().size == orderIds.size) {
            "Demand order IDs must be unique within a request."
        }
        val previouslyProcessedOrderIds = recordRepository.findAllByOrderIdIn(orderIds)
            .map { it.orderId }
            .distinct()
        require(previouslyProcessedOrderIds.isEmpty()) {
            "Orders have already been processed: ${previouslyProcessedOrderIds.joinToString()}. Fulfill or resolve existing backorders instead of submitting those orders again."
        }

        registerInboundSupply(request.inboundShipments)
        val inboundSupplyById = inboundSupplyRepository.lockAllUnreceived().associateBy { it.shipmentId }
        val stockBySku = lockStock(request.demandOrders.map { it.sku }.distinct(), request.asOfDate)
        val calculation = allocationCalculation.calculate(
            orders = request.demandOrders.map {
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
            asOfDate = request.asOfDate,
            inventory = stockBySku.values.flatten().map(::toInventoryLotState),
            inboundSupply = inboundSupplyById.values.filterNot { it.received }.map(::toInboundSupplyState),
            stockReservations = loadReservations(orderIds).values.map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(orderIds).values.map(::toInboundReservationChange),
            registerBackorders = true
        )
        val now = Instant.now()
        persistCalculationChanges(calculation, stockBySku, inboundSupplyById, now)
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
                    createdAt = now,
                    updatedAt = now
                )
            }
        )

        val result = persistRun(
            requestId = request.requestId,
            requestHash = requestHash,
            runType = AllocationRunType.NEW_DEMAND,
            asOfDate = request.asOfDate,
            allocations = calculation.allocations
        )
        return result
    }

    @Transactional
    fun fulfillOpenBackorders(request: BackorderFulfillmentRequest): AllocationRunResult {
        validate(request)
        validateSources(request.inboundShipments)
        val requestHash = requestHash(request)
        runRepository.findByRequestId(request.requestId)?.let { previousRun ->
            require(
                previousRun.runType == AllocationRunType.BACKORDER_FULFILLMENT &&
                    previousRun.requestHash == requestHash
            ) {
                "requestId '${request.requestId}' has already been used with a different request."
            }
            return getRun(previousRun.id)
        }

        val pendingBackorders = backorderRepository.findAllByOrderByPriorityAscPlacedDateAscCreatedAtAscOrderIdAsc()
        registerInboundSupply(request.inboundShipments)
        val inboundSupplyById = inboundSupplyRepository.lockAllUnreceived().associateBy { it.shipmentId }
        val stockBySku = lockStock(pendingBackorders.map { it.sku }.distinct(), request.asOfDate)
        val eligibleSupply = inboundSupplyById.values.filter {
            !it.received && it.expectedArrivalDate <= request.asOfDate
        }
        val orderIds = pendingBackorders.map { it.orderId }
        val calculation = allocationCalculation.calculate(
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
            asOfDate = request.asOfDate,
            inventory = stockBySku.values.flatten().map(::toInventoryLotState),
            inboundSupply = eligibleSupply.map(::toInboundSupplyState),
            stockReservations = loadReservations(orderIds).values.map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(orderIds).values.map(::toInboundReservationChange),
            registerBackorders = false
        )
        val now = Instant.now()
        val backordersByOrderId = pendingBackorders.associateBy { it.orderId }
        val allocations = calculation.allocations.map { allocation ->
            val backorder = backordersByOrderId.getValue(allocation.orderId)
            val allocated = allocation.allocatedFromInventory + allocation.allocatedFromInbound
            if (allocation.backorderedQuantity == 0) {
                backorderRepository.delete(backorder)
            } else {
                backorder.remainingQuantity = allocation.backorderedQuantity
                backorder.updatedAt = now
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
        persistCalculationChanges(calculation, stockBySku, inboundSupplyById, now)
        val result = persistRun(
            requestId = request.requestId,
            requestHash = requestHash,
            runType = AllocationRunType.BACKORDER_FULFILLMENT,
            asOfDate = request.asOfDate,
            allocations = allocations
        )
        return result
    }

    @Transactional(readOnly = true)
    fun getRun(runId: UUID): AllocationRunResult {
        val run = runRepository.findById(runId)
            .orElseThrow { IllegalArgumentException("Allocation run '$runId' was not found.") }
        val entities = recordRepository.findAllByRunIdOrderBySequenceNumber(runId)
        val sourcesByRecordId = if (entities.isEmpty()) {
            emptyMap()
        } else {
            sourceRepository.findAllByAllocationRecordIdIn(entities.map { it.id })
                .groupBy { it.allocationRecordId }
        }

        val allocations = entities.map { entity ->
            AllocationRecord(
                orderId = entity.orderId,
                customer = entity.customer,
                sku = entity.sku,
                priority = entity.priority,
                placedDate = entity.placedDate,
                requiredDeliveryDate = entity.requiredDeliveryDate,
                requestedQuantity = entity.requestedQuantity,
                allocatedFromInventory = entity.allocatedFromInventory,
                allocatedFromInbound = entity.allocatedFromInbound,
                backorderedQuantity = entity.backorderedQuantity,
                status = entity.status,
                sources = sourcesByRecordId[entity.id].orEmpty().map {
                    AllocationSource(
                        sourceType = it.sourceType,
                        sourceId = it.sourceId,
                        quantity = it.quantity,
                        sourceDate = it.sourceDate,
                        warehouseLocation = it.warehouseLocation
                    )
                }
            )
        }

        return AllocationRunResult(
            runId = run.id,
            requestId = run.requestId,
            runType = run.runType,
            asOfDate = run.asOfDate,
            createdAt = run.createdAt,
            openBackorderCount = run.openBackorderCount,
            allocations = allocations
        )
    }

    private fun toInventoryLotState(stock: StockEntity) = InventoryLotState(
        stockId = stock.id,
        sku = stock.sku,
        lotId = stock.lotId,
        warehouseCode = stock.warehouseCode,
        receivedDate = stock.receivedDate,
        quantityOnHand = stock.quantityOnHand,
        quantityReserved = stock.quantityReserved
    )

    private fun toInboundSupplyState(supply: InboundSupplyEntity) = InboundSupplyState(
        shipmentId = supply.shipmentId,
        sku = supply.sku,
        warehouseCode = supply.warehouseCode,
        expectedQuantity = supply.expectedQuantity,
        expectedArrivalDate = supply.expectedArrivalDate,
        quantityReserved = supply.quantityReserved
    )

    private fun toStockReservationChange(reservation: StockReservationEntity) = StockReservationChange(
        id = reservation.id,
        orderId = reservation.orderId,
        stockId = reservation.stockId,
        quantity = reservation.quantity,
        requiredDeliveryDate = reservation.requiredDeliveryDate,
        createdAt = reservation.createdAt
    )

    private fun toInboundReservationChange(reservation: InboundReservationEntity) = InboundReservationChange(
        id = reservation.id,
        orderId = reservation.orderId,
        shipmentId = reservation.shipmentId,
        quantity = reservation.quantity,
        requiredDeliveryDate = reservation.requiredDeliveryDate,
        createdAt = reservation.createdAt
    )

    private fun persistCalculationChanges(
        calculation: AllocationCalculationResult,
        stockBySku: Map<String, List<StockEntity>>,
        inboundSupplyById: Map<String, InboundSupplyEntity>,
        now: Instant
    ) {
        val stockById = stockBySku.values.flatten().associateBy { it.id }
        calculation.inventoryLots.forEach { lot ->
            stockById.getValue(lot.stockId).quantityReserved = lot.quantityReserved
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
                    createdAt = reservation.createdAt ?: now
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
                    createdAt = reservation.createdAt ?: now
                )
            }
        )
    }

    private fun persistRun(
        requestId: UUID,
        requestHash: String,
        runType: AllocationRunType,
        asOfDate: java.time.LocalDate,
        allocations: List<AllocationRecord>
    ): AllocationRunResult {
        val openBackorderCount = backorderRepository.count().toInt()
        val run = runRepository.save(
            AllocationRunEntity(
                requestId = requestId,
                requestHash = requestHash,
                runType = runType,
                asOfDate = asOfDate,
                openBackorderCount = openBackorderCount,
                createdAt = Instant.now()
            )
        )
        val recordEntities = allocations.mapIndexed { index, allocation ->
            AllocationRecordEntity(
                runId = run.id,
                sequenceNumber = index,
                orderId = allocation.orderId,
                customer = allocation.customer,
                sku = allocation.sku,
                priority = allocation.priority,
                placedDate = allocation.placedDate,
                requiredDeliveryDate = allocation.requiredDeliveryDate,
                requestedQuantity = allocation.requestedQuantity,
                allocatedFromInventory = allocation.allocatedFromInventory,
                allocatedFromInbound = allocation.allocatedFromInbound,
                backorderedQuantity = allocation.backorderedQuantity,
                status = allocation.status
            )
        }
        val savedRecords = recordRepository.saveAll(recordEntities)
        val sourceEntities = savedRecords.zip(allocations).flatMap { (record, allocation) ->
            allocation.sources.map { source ->
                AllocationSourceEntity(
                    allocationRecordId = record.id,
                    sourceType = source.sourceType,
                    sourceId = source.sourceId,
                    quantity = source.quantity,
                    sourceDate = source.sourceDate,
                    warehouseLocation = source.warehouseLocation
                )
            }
        }
        sourceRepository.saveAll(sourceEntities)

        return AllocationRunResult(
            runId = run.id,
            requestId = run.requestId,
            runType = run.runType,
            asOfDate = run.asOfDate,
            createdAt = run.createdAt,
            openBackorderCount = run.openBackorderCount,
            allocations = allocations
        )
    }

    private fun validateSources(
        inboundShipments: List<InboundShipment>
    ) {
        validateBean(inboundShipments)
        require(inboundShipments.map { it.shipmentId }.distinct().size == inboundShipments.size) {
            "Inbound shipment IDs must be unique within a request."
        }
    }

    private fun lockStock(skus: List<String>, asOfDate: java.time.LocalDate): Map<String, List<StockEntity>> =
        if (skus.isEmpty()) {
            emptyMap()
        } else {
            stockRepository.lockAllBySkuIn(skus)
                .filter { it.receivedDate <= asOfDate }
                .groupBy { it.sku }
                .mapValues { (_, lots) ->
                    lots.sortedWith(compareBy<StockEntity> { it.receivedDate }.thenBy { it.lotId }.thenBy { it.warehouseCode })
                }
        }

    private fun registerInboundSupply(shipments: List<InboundShipment>) {
        if (shipments.isEmpty()) return
        val ids = shipments.map { it.shipmentId }
        val supplies = if (ids.isEmpty()) {
            mutableMapOf()
        } else {
            inboundSupplyRepository.lockAllByShipmentIdIn(ids).associateByTo(mutableMapOf()) { it.shipmentId }
        }
        shipments.forEach { shipment ->
            val existing = supplies[shipment.shipmentId]
            if (existing == null) {
                supplies[shipment.shipmentId] = InboundSupplyEntity(
                    shipmentId = shipment.shipmentId,
                    sku = shipment.sku,
                    warehouseCode = shipment.warehouseCode,
                    expectedQuantity = shipment.expectedQuantity,
                    expectedArrivalDate = shipment.expectedArrivalDate
                )
            } else {
                require(
                    existing.sku == shipment.sku &&
                        existing.warehouseCode == shipment.warehouseCode &&
                        existing.expectedArrivalDate == shipment.expectedArrivalDate
                ) {
                    "Inbound shipment ID '${shipment.shipmentId}' has already been used with different SKU, warehouse, or arrival date."
                }
                require(shipment.expectedQuantity >= existing.quantityReserved) {
                    "Inbound shipment '${shipment.shipmentId}' cannot be reduced below its reserved quantity."
                }
                existing.expectedQuantity = shipment.expectedQuantity
            }
        }
        inboundSupplyRepository.saveAll(supplies.values)
    }

    private fun loadReservations(orderIds: Collection<String>): MutableMap<Pair<String, UUID>, StockReservationEntity> =
        if (orderIds.isEmpty()) {
            mutableMapOf()
        } else {
            stockReservationRepository.lockAllByOrderIdIn(orderIds)
                .associateByTo(mutableMapOf()) { it.orderId to it.stockId }
        }

    private fun loadInboundReservations(
        orderIds: Collection<String>
    ): MutableMap<Pair<String, String>, InboundReservationEntity> =
        if (orderIds.isEmpty()) {
            mutableMapOf()
        } else {
            inboundReservationRepository.lockAllByOrderIdIn(orderIds)
                .associateByTo(mutableMapOf()) { it.orderId to it.shipmentId }
        }

    private fun validate(request: AllocationRequest) {
        validateBean(request)
        require(request.demandOrders.all { it.placedDate <= it.requiredDeliveryDate }) {
            "An order's placed date must be on or before its required delivery date."
        }
    }

    private fun validate(request: BackorderFulfillmentRequest) {
        validateBean(request)
    }

    private fun validateBean(value: Any) {
        val violations = validator.validate(value)
        require(violations.isEmpty()) {
            violations.sortedBy { it.propertyPath.toString() }
                .joinToString("; ") { "${it.propertyPath}: ${it.message}" }
        }
    }

    private fun requestHash(value: Any): String =
        MessageDigest.getInstance("SHA-256")
            .digest(objectMapper.writeValueAsBytes(value))
            .joinToString("") { "%02x".format(it) }
}

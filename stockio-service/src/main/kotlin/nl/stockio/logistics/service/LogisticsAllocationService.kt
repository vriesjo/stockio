package nl.stockio.logistics.service

import jakarta.validation.Validator
import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.logistics.domain.AllocationRecord
import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.domain.AllocationRunResult
import nl.stockio.logistics.domain.AllocationRunType
import nl.stockio.logistics.domain.AllocationSource
import nl.stockio.logistics.domain.AllocationSourceType
import nl.stockio.logistics.domain.AllocationStatus
import nl.stockio.logistics.domain.BackorderFulfillmentRequest
import nl.stockio.logistics.domain.DemandOrder
import nl.stockio.logistics.domain.InboundShipment
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
import kotlin.math.min

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

    // Allocation process for new demand orders
    // This method validates the request, checks for duplicate order IDs, and ensures that the orders have not been processed before.
    // It then registers inbound supply, locks stock and inbound supply, and allocates inventory and inbound supply to fulfill the demand orders.
    // The allocations are persisted, and the stock projection is rebuilt.
    // Rebuilding the stock projection ensures that the system has an up-to-date view of available stock after the allocation process.
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
        // Load existing reservations for the orders being processed, both for stock and inbound supply.
        val reservationsByOrderAndStock = loadReservations(orderIds)

        // Load existing inbound reservations for the orders being processed, which helps in tracking allocations from inbound shipments.
        // This is important for ensuring that the system does not over-allocate from inbound shipments
        // that have already been reserved for other orders.
        val inboundReservationsByOrderAndShipment = loadInboundReservations(orderIds)

        // Filter out inbound supply that has already been received, as it is no longer available for allocation.
        // This ensures that only inbound shipments that are still expected to arrive are considered for fulfilling demand orders.
        val availableSupply = inboundSupplyById.values.filterNot { it.received }
        val inboundRemaining = availableSupply.associate { it.shipmentId to it.quantityAvailable }.toMutableMap()
        val inboundBySku = availableSupply.groupBy { it.sku }.mapValues { (_, shipments) ->
            shipments.sortedWith(compareBy<InboundSupplyEntity> { it.expectedArrivalDate }.thenBy { it.shipmentId })
        }

        val sortedOrders = request.demandOrders.withIndex()
            .sortedWith(
                compareBy<IndexedValue<DemandOrder>> { it.value.priority }
                    .thenBy { it.value.placedDate }
                    .thenBy { it.index }
            )
            .map { it.value }

        // Allocate inventory and inbound supply for each order in the sorted list,
        val allocations = sortedOrders.map { order ->
            val sources = mutableListOf<AllocationSource>()
            var remaining = order.requestedQuantity
            // Allocate from inventory first, then from inbound supply, updating the remaining quantity to be fulfilled.
            val fromInventory = allocateInventory(
                order.sku,
                remaining,
                stockBySku,
                sources,
                order.orderId,
                reservationsByOrderAndStock,
                order.requiredDeliveryDate.coerceAtLeast(request.asOfDate)
            ).also { remaining -= it }
            // Allocate from inbound supply, considering only shipments that are expected to arrive by the required delivery date.
            val fromInbound = allocateInbound(
                order.sku,
                order.requiredDeliveryDate,
                remaining,
                inboundBySku,
                inboundRemaining,
                sources,
                order.orderId,
                order.requiredDeliveryDate.coerceAtLeast(request.asOfDate),
                inboundReservationsByOrderAndShipment
            ).also { remaining -= it }

            AllocationRecord(
                orderId = order.orderId,
                customer = order.customer,
                sku = order.sku,
                priority = order.priority,
                placedDate = order.placedDate,
                requiredDeliveryDate = order.requiredDeliveryDate,
                requestedQuantity = order.requestedQuantity,
                allocatedFromInventory = fromInventory,
                allocatedFromInbound = fromInbound,
                backorderedQuantity = remaining,
                status = when {
                    remaining == 0 -> AllocationStatus.ALLOCATED
                    fromInventory + fromInbound > 0 -> AllocationStatus.PARTIALLY_BACKORDERED
                    else -> AllocationStatus.BACKORDERED
                },
                sources = sources
            )
        }

        val now = Instant.now()
        // Persist reservations and create backorder records for any orders not fully fulfilled.
        stockReservationRepository.saveAll(reservationsByOrderAndStock.values)
        inboundSupplyRepository.saveAll(inboundSupplyById.values)
        inboundReservationRepository.saveAll(inboundReservationsByOrderAndShipment.values)
        backorderRepository.saveAll(
            allocations.filter { it.backorderedQuantity > 0 }.map { allocation ->
                OpenBackorderEntity(
                    orderId = allocation.orderId,
                    customer = allocation.customer,
                    sku = allocation.sku,
                    priority = allocation.priority,
                    placedDate = allocation.placedDate,
                    requiredDeliveryDate = allocation.requiredDeliveryDate,
                    originalQuantity = allocation.requestedQuantity,
                    remainingQuantity = allocation.backorderedQuantity,
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
            allocations = allocations
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
        val reservationsByOrderAndStock = loadReservations(pendingBackorders.map { it.orderId })
        val inboundReservationsByOrderAndShipment = loadInboundReservations(pendingBackorders.map { it.orderId })
        val eligibleSupply = inboundSupplyById.values.filter {
            !it.received && it.expectedArrivalDate <= request.asOfDate
        }
        val inboundRemaining = eligibleSupply.associate { it.shipmentId to it.quantityAvailable }.toMutableMap()
        val inboundBySku = eligibleSupply.groupBy { it.sku }.mapValues { (_, shipments) ->
            shipments.sortedWith(compareBy<InboundSupplyEntity> { it.expectedArrivalDate }.thenBy { it.shipmentId })
        }

        val now = Instant.now()
        val allocations = pendingBackorders
            .mapIndexed { index, backorder ->
                val sources = mutableListOf<AllocationSource>()
                val quantityBeforeFulfillment = backorder.remainingQuantity
                var remaining = quantityBeforeFulfillment
                val fulfillmentDate = backorder.requiredDeliveryDate.coerceAtLeast(request.asOfDate)
                val fromInventory = allocateInventory(
                    backorder.sku,
                    remaining,
                    stockBySku,
                    sources,
                    backorder.orderId,
                    reservationsByOrderAndStock,
                    fulfillmentDate
                ).also { remaining -= it }
                val fromInbound = allocateInbound(
                    backorder.sku,
                    backorder.requiredDeliveryDate,
                    remaining,
                    inboundBySku,
                    inboundRemaining,
                    sources,
                    backorder.orderId,
                    fulfillmentDate,
                    inboundReservationsByOrderAndShipment
                ).also { remaining -= it }
                val allocated = fromInventory + fromInbound

                if (remaining == 0) {
                    backorderRepository.delete(backorder)
                } else {
                    backorder.remainingQuantity = remaining
                    backorder.updatedAt = now
                    backorderRepository.save(backorder)
                }

                AllocationRecord(
                    orderId = backorder.orderId,
                    customer = backorder.customer,
                    sku = backorder.sku,
                    priority = backorder.priority,
                    placedDate = backorder.placedDate,
                    requiredDeliveryDate = backorder.requiredDeliveryDate,
                    requestedQuantity = quantityBeforeFulfillment,
                    allocatedFromInventory = fromInventory,
                    allocatedFromInbound = fromInbound,
                    backorderedQuantity = remaining,
                    status = when {
                        remaining == 0 -> AllocationStatus.FULFILLED
                        allocated > 0 -> AllocationStatus.PARTIALLY_FULFILLED
                        else -> AllocationStatus.BACKORDERED
                    },
                    sources = sources
                )
            }

        stockReservationRepository.saveAll(reservationsByOrderAndStock.values)
        inboundSupplyRepository.saveAll(inboundSupplyById.values)
        inboundReservationRepository.saveAll(inboundReservationsByOrderAndShipment.values)
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

    private fun allocateInventory(
        sku: String,
        requested: Int,
        lotsBySku: Map<String, List<StockEntity>>,
        sources: MutableList<AllocationSource>,
        orderId: String,
        reservationsByOrderAndStock: MutableMap<Pair<String, UUID>, StockReservationEntity>,
        deliveryDate: java.time.LocalDate
    ): Int {
        var remaining = requested
        // Iterate over the lots for the given SKU,
        // allocating from inventory until the requested quantity is fulfilled
        // or there are no more available lots.
        for (lot in lotsBySku[sku].orEmpty()) {
            if (remaining == 0) break
            val available = lot.quantityAvailable
            val allocated = min(remaining, available)
            if (allocated > 0) {
                lot.quantityReserved += allocated
                val key = orderId to lot.id
                val reservation = reservationsByOrderAndStock.getOrPut(key) {
                    StockReservationEntity(
                        orderId = orderId,
                        stockId = lot.id,
                        requiredDeliveryDate = deliveryDate
                    )
                }
                reservation.quantity += allocated
                reservation.requiredDeliveryDate = deliveryDate
                remaining -= allocated
                sources += AllocationSource(
                    sourceType = AllocationSourceType.INVENTORY,
                    sourceId = lot.lotId,
                    quantity = allocated,
                    sourceDate = lot.receivedDate,
                    warehouseLocation = lot.warehouseCode
                )
            }
        }
        return requested - remaining
    }

    private fun allocateInbound(
        sku: String,
        latestArrivalDate: java.time.LocalDate,
        requested: Int,
        shipmentsBySku: Map<String, List<InboundSupplyEntity>>,
        remainingByShipment: MutableMap<String, Int>,
        sources: MutableList<AllocationSource>,
        orderId: String,
        deliveryDate: java.time.LocalDate,
        reservationsByOrderAndShipment: MutableMap<Pair<String, String>, InboundReservationEntity>
    ): Int {
        var remaining = requested
        // Allocate from reserved inbound supply only when it arrives by the required delivery date.
        for (shipment in shipmentsBySku[sku].orEmpty()) {
            if (remaining == 0) break
            if (shipment.expectedArrivalDate > latestArrivalDate) continue
            val available = remainingByShipment.getValue(shipment.shipmentId)
            val allocated = min(remaining, available)
            if (allocated > 0) {
                remainingByShipment[shipment.shipmentId] = available - allocated
                shipment.quantityReserved += allocated
                val key = orderId to shipment.shipmentId
                val reservation = reservationsByOrderAndShipment.getOrPut(key) {
                    InboundReservationEntity(
                        orderId = orderId,
                        shipmentId = shipment.shipmentId,
                        requiredDeliveryDate = deliveryDate
                    )
                }
                reservation.quantity += allocated
                reservation.requiredDeliveryDate = deliveryDate
                remaining -= allocated
                sources += AllocationSource(
                    sourceType = AllocationSourceType.INBOUND,
                    sourceId = shipment.shipmentId,
                    quantity = allocated,
                    sourceDate = shipment.expectedArrivalDate,
                    warehouseLocation = shipment.warehouseCode
                )
            }
        }
        return requested - remaining
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

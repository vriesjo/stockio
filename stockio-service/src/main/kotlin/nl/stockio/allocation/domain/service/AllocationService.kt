package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunResult
import nl.stockio.allocation.domain.bean.AllocationRunType
import nl.stockio.allocation.domain.bean.AllocationStatus
import nl.stockio.allocation.domain.converter.toInboundReservationChange
import nl.stockio.allocation.domain.converter.toInboundSupplyState
import nl.stockio.allocation.domain.converter.toInventoryLotState
import nl.stockio.allocation.domain.converter.toStockReservationChange
import nl.stockio.allocation.domain.model.AllocationCalculationResult
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.policy.AllocationPolicyCalculator
import nl.stockio.allocation.domain.policy.InboundAllocationPolicy
import nl.stockio.allocation.domain.policy.InventoryAllocationPolicy
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
import nl.stockio.util.requestHash
import nl.stockio.allocation.validator.AllocationRequestValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationService(
    private val allocationRequestValidator: AllocationRequestValidator,
    private val allocationRunService: AllocationRunService,
    private val inboundSupplyService: InboundSupplyService,
    private val backorderRepository: OpenBackorderRepository,
    private val stockRepository: StockRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val inboundSupplyRepository: InboundSupplyRepository,
    private val inboundReservationRepository: InboundReservationRepository,
) {

    private val allocationPolicyCalculator = AllocationPolicyCalculator(
        InventoryAllocationPolicy(),
        InboundAllocationPolicy()
    )

    // This method processes new demand:
    // If stock and eligible inbound supply cannot cover an order, it creates an open backorder for the remaining quantity
    @Transactional
    fun allocation(request: AllocationRequest): AllocationRunResult {
        allocationRequestValidator.validate(request)

        allocationRunService.getRun(request) { prevRun, alloReq ->
            prevRun.runType == AllocationRunType.NEW_DEMAND &&
                prevRun.requestHash == requestHash(alloReq)
        }

        val orderIds = request.demandOrders.map { it.orderId }

        inboundSupplyService.registerInboundSupply(request.inboundShipments)

        val lockedInboundSupplyById = lockedAllUnreceivedInboundSupply()
        val stockBySku = lockStock(request.demandOrders.map { it.sku }.distinct(), request.asOfDate)

        val calculation = allocationPolicyCalculator.calculate(
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
            inboundSupply = lockedInboundSupplyById.values.filterNot { it.received }.map(::toInboundSupplyState),
            stockReservations = loadReservations(orderIds).values.map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(orderIds).values.map(::toInboundReservationChange),
            registerBackorders = true
        )
        saveCalculationChanges(calculation, stockBySku, lockedInboundSupplyById)

        val result = allocationRunService.persistRun(
            requestId = request.requestId,
            requestHash = requestHash(request),
            runType = AllocationRunType.NEW_DEMAND,
            asOfDate = request.asOfDate,
            allocations = calculation.allocations
        )
        return result
    }

    // This method processes backorders that already exist in the database.
    // It tries to fulfill them using current stock and inbound shipments that have arrived by asOfDate,
    // then updates or removes those backorders.
    @Transactional
    fun allocateOpenBackorders(request: AllocationRequest): AllocationRunResult {
        allocationRequestValidator.validate(request)

        allocationRunService.getRun(request) { prevRun, req ->
            prevRun.runType == AllocationRunType.BACKORDER_FULFILLMENT &&
                prevRun.requestHash == requestHash(req)
        }

        inboundSupplyService.registerInboundSupply(request.inboundShipments)

        val pendingBackorders = backorderRepository.findAllByOrderByPriorityAscPlacedDateAscCreatedAtAscOrderIdAsc()
        val inboundSupplyById = lockedAllUnreceivedInboundSupply()
        val stockBySku = lockStock(pendingBackorders.map { it.sku }.distinct(), request.asOfDate)

        val eligibleSupply = inboundSupplyById.values.filter {
            !it.received && it.expectedArrivalDate <= request.asOfDate
        }
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
            asOfDate = request.asOfDate,
            inventory = stockBySku.values.flatten().map(::toInventoryLotState),
            inboundSupply = eligibleSupply.map(::toInboundSupplyState),
            stockReservations = loadReservations(orderIds).values.map(::toStockReservationChange),
            inboundReservations = loadInboundReservations(orderIds).values.map(::toInboundReservationChange),
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
        saveCalculationChanges(calculation, stockBySku, inboundSupplyById)
        val result = allocationRunService.persistRun(
            requestId = request.requestId,
            requestHash = requestHash(request),
            runType = AllocationRunType.BACKORDER_FULFILLMENT,
            asOfDate = request.asOfDate,
            allocations = allocations
        )
        return result
    }

    private fun lockedAllUnreceivedInboundSupply(): Map<String, InboundSupplyEntity> =
        inboundSupplyRepository.lockAllUnreceived().associateBy { it.shipmentId }

    private fun saveCalculationChanges(
        calculation: AllocationCalculationResult,
        stockBySku: Map<String, List<StockEntity>>,
        inboundSupplyById: Map<String, InboundSupplyEntity>,
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

    private fun lockStock(skus: List<String>, asOfDate: LocalDate): Map<String, List<StockEntity>> =
        if (skus.isEmpty()) {
            emptyMap()
        } else {
            stockRepository.lockAllBySkuIn(skus)
                .filter { it.receivedDate <= asOfDate }
                .groupBy { it.sku }
                .mapValues { (_, lots) ->
                    lots.sortedWith(compareBy<StockEntity> { it.receivedDate }.thenBy { it.lotId }
                        .thenBy { it.warehouseCode })
                }
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

}

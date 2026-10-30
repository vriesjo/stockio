package nl.stockio.allocation.domain.worker

import java.time.LocalDate
import nl.stockio.allocation.domain.model.AllocationCalculationResult
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationStatus
import nl.stockio.allocation.domain.model.BackorderRegistration
import nl.stockio.allocation.domain.model.InboundReservationChange
import nl.stockio.allocation.domain.model.InboundSupplyState
import nl.stockio.allocation.domain.model.InventoryLotState
import nl.stockio.allocation.domain.model.StockReservationChange

class AllocationCalculator(
    private val inventoryAllocationPolicy: InventoryAllocationPolicy,
    private val inboundAllocationPolicy: InboundAllocationPolicy
) {

    fun calculate(
        orders: List<AllocationOrder>,
        asOfDate: LocalDate,
        inventory: List<InventoryLotState>,
        inboundSupply: List<InboundSupplyState>,
        stockReservations: List<StockReservationChange>,
        inboundReservations: List<InboundReservationChange>,
        registerBackorders: Boolean
    ): AllocationCalculationResult {
        val mutableInventory = inventory.map { it.copy() }
        val mutableInboundSupply = inboundSupply.map { it.copy() }
        val inventoryBySku = mutableInventory
            .groupBy { it.sku }
            .mapValues { (_, lots) ->
                lots.sortedWith(compareBy<InventoryLotState> { it.receivedDate }.thenBy { it.lotId }.thenBy { it.warehouseCode })
            }
        val inboundBySku = mutableInboundSupply
            .groupBy { it.sku }
            .mapValues { (_, supplies) ->
                supplies.sortedWith(compareBy<InboundSupplyState> { it.expectedArrivalDate }
                    .thenBy { it.shipmentId }.thenBy { it.shipmentLineId })
            }
        val inboundRemaining = mutableInboundSupply.associate {
            it.shipmentLineId to (it.expectedQuantity - it.quantityReserved)
        }.toMutableMap()
        val stockReservationsByKey = stockReservations.associateByTo(mutableMapOf()) { it.orderLineId to it.stockId }
        val inboundReservationsByKey = inboundReservations.associateByTo(mutableMapOf()) {
            it.orderLineId to it.shipmentLineId
        }

        val allocations = orders.withIndex()
            .sortedWith(
                compareBy<IndexedValue<AllocationOrder>> { it.value.priority }
                    .thenBy { it.value.placedDate }
                    .thenBy { it.index }
            )
            .map { indexedOrder ->
                val order = indexedOrder.value
                val sources = mutableListOf<AllocationSource>()
                var remaining = order.quantity
                val deliveryDate = order.requiredDeliveryDate.coerceAtLeast(asOfDate)
                val fromInventory = inventoryAllocationPolicy.allocate(
                    sku = order.sku,
                    requested = remaining,
                    lotsBySku = inventoryBySku,
                    sources = sources,
                    orderId = order.orderId,
                    orderLineId = order.orderLineId,
                    reservationsByOrderAndStock = stockReservationsByKey,
                    deliveryDate = deliveryDate
                ).also { remaining -= it }
                val fromInbound = inboundAllocationPolicy.allocate(
                    sku = order.sku,
                    latestArrivalDate = order.requiredDeliveryDate,
                    requested = remaining,
                    shipmentsBySku = inboundBySku,
                    remainingByShipment = inboundRemaining,
                    sources = sources,
                    orderId = order.orderId,
                    orderLineId = order.orderLineId,
                    deliveryDate = deliveryDate,
                    reservationsByOrderAndShipment = inboundReservationsByKey
                ).also { remaining -= it }
                AllocationRecord(
                    orderId = order.orderId,
                    orderLineId = order.orderLineId,
                    customer = order.customer,
                    sku = order.sku,
                    priority = order.priority,
                    placedDate = order.placedDate,
                    requiredDeliveryDate = order.requiredDeliveryDate,
                    requestedQuantity = order.quantity,
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

        val backorders = if (registerBackorders) {
            allocations.filter { it.backorderedQuantity > 0 }.map {
                BackorderRegistration(
                    orderId = it.orderId,
                    orderLineId = it.orderLineId,
                    customer = it.customer,
                    sku = it.sku,
                    priority = it.priority,
                    placedDate = it.placedDate,
                    requiredDeliveryDate = it.requiredDeliveryDate,
                    originalQuantity = it.requestedQuantity,
                    quantity = it.backorderedQuantity
                )
            }
        } else {
            emptyList()
        }

        return AllocationCalculationResult(
            allocations = allocations,
            inventoryLots = mutableInventory,
            inboundSupplies = mutableInboundSupply,
            stockReservations = stockReservationsByKey.values.toList(),
            inboundReservations = inboundReservationsByKey.values.toList(),
            backordersToRegister = backorders
        )
    }
}

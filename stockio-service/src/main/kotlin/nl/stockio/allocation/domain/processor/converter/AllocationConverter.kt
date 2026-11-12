package nl.stockio.allocation.domain.processor.converter

import nl.stockio.allocation.domain.model.InboundReservationChange
import nl.stockio.allocation.domain.model.InboundSupplyState
import nl.stockio.allocation.domain.model.InventoryLotState
import nl.stockio.allocation.domain.model.StockReservationChange
import nl.stockio.reservation.infra.InboundReservationEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import nl.stockio.stock.infra.StockEntity
import nl.stockio.stock.infra.StockReservationEntity

fun toInventoryLotState(stock: StockEntity) =
    InventoryLotState(
        stockId = stock.id,
        sku = stock.sku,
        lotId = stock.lotId,
        warehouseCode = stock.warehouseCode,
        receivedDate = stock.receivedDate,
        quantityOnHand = stock.quantityOnHand,
        quantityReserved = stock.quantityReserved,
    )

fun toInboundSupplyState(supply: InboundSupplyEntity) =
    InboundSupplyState(
        shipmentId = supply.shipmentId,
        shipmentLineId = supply.shipmentLineId,
        sku = supply.sku,
        warehouseCode = supply.warehouseCode,
        expectedQuantity = supply.expectedQuantity,
        expectedArrivalDate = supply.expectedArrivalDate,
        quantityReserved = supply.quantityReserved,
    )

fun toStockReservationChange(reservation: StockReservationEntity) =
    StockReservationChange(
        id = reservation.id,
        orderId = reservation.orderId,
        orderLineId = reservation.orderLineId,
        stockId = reservation.stockId,
        quantity = reservation.quantity,
        requiredDeliveryDate = reservation.requiredDeliveryDate,
        createdAt = reservation.createdAt,
    )

fun toInboundReservationChange(reservation: InboundReservationEntity) =
    InboundReservationChange(
        id = reservation.id,
        orderId = reservation.orderId,
        orderLineId = reservation.orderLineId,
        shipmentId = reservation.shipmentId,
        shipmentLineId = reservation.shipmentLineId,
        quantity = reservation.quantity,
        requiredDeliveryDate = reservation.requiredDeliveryDate,
        createdAt = reservation.createdAt,
    )

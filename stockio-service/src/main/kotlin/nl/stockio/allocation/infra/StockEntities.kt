package nl.stockio.allocation.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "stock_projection_lock")
class StockProjectionLockEntity(
    @Id
    var id: Int = 1
)

data class StockDailyLotSnapshotId(
    var snapshotDate: LocalDate = LocalDate.MIN,
    var sku: String = "",
    var warehouseCode: String = "",
    var lotId: String = ""
) : Serializable

@Entity
@Table(
    name = "stock",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_stock_lot",
        columnNames = ["sku", "warehouse_code", "lot_id"]
    )]
)
class StockEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 64)
    var sku: String = "",
    @Column(name = "warehouse_code", nullable = false, length = 50)
    var warehouseCode: String = "MAIN",
    @Column(name = "lot_id", nullable = false, length = 64)
    var lotId: String = "",
    @Column(name = "received_date", nullable = false)
    var receivedDate: LocalDate = LocalDate.now(),
    @Column(name = "quantity_on_hand", nullable = false)
    var quantityOnHand: Int = 0,
    @Column(name = "quantity_reserved", nullable = false)
    var quantityReserved: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    val quantityAvailable: Int
        get() = quantityOnHand - quantityReserved
}

@Entity
@Table(
    name = "stock_daily_snapshots",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_daily_snapshot",
        columnNames = ["snapshot_date", "sku", "warehouse_code"]
    )]
)
class StockDailySnapshotEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "snapshot_date", nullable = false)
    var snapshotDate: LocalDate = LocalDate.now(),
    @Column(nullable = false, length = 64)
    var sku: String = "",
    @Column(name = "warehouse_code", nullable = false, length = 50)
    var warehouseCode: String = "MAIN",
    @Column(name = "opening_stock", nullable = false)
    var openingStock: Int = 0,
    @Column(name = "inbound_qty", nullable = false)
    var inboundQuantity: Int = 0,
    @Column(name = "demand_qty", nullable = false)
    var demandQuantity: Int = 0,
    @Column(name = "allocated_qty", nullable = false)
    var allocatedQuantity: Int = 0,
    @Column(name = "closing_stock", nullable = false)
    var closingStock: Int = 0,
    @Column(name = "shortage_qty", nullable = false)
    var shortageQuantity: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

@Entity
@Table(name = "allocation_job_result")
class AllocationJobResultEntity(
    @Id
    @Column(name = "job_id", nullable = false)
    var jobId: UUID = UUID.randomUUID(),
    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    var resultJson: String = ""
)

@Entity
@IdClass(StockDailyLotSnapshotId::class)
@Table(name = "stock_daily_lot_snapshots")
class StockDailyLotSnapshotEntity(
    @Id
    @Column(name = "snapshot_date", nullable = false)
    var snapshotDate: LocalDate = LocalDate.now(),
    @Id
    @Column(nullable = false, length = 64)
    var sku: String = "",
    @Id
    @Column(name = "warehouse_code", nullable = false, length = 50)
    var warehouseCode: String = "MAIN",
    @Id
    @Column(name = "lot_id", nullable = false, length = 64)
    var lotId: String = "",
    @Column(name = "received_date", nullable = false)
    var receivedDate: LocalDate = LocalDate.now(),
    @Column(name = "opening_stock", nullable = false)
    var openingStock: Int = 0,
    @Column(name = "inbound_qty", nullable = false)
    var inboundQuantity: Int = 0,
    @Column(name = "allocated_qty", nullable = false)
    var allocatedQuantity: Int = 0,
    @Column(name = "closing_stock", nullable = false)
    var closingStock: Int = 0,
    @Column(name = "quantity_reserved", nullable = false)
    var quantityReserved: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

@Entity
@Table(
    name = "stock_reservation",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_stock_reservation_order_lot",
        columnNames = ["order_line_id", "stock_id"]
    )]
)
class StockReservationEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "order_id", nullable = false, length = 100)
    var orderId: String = "",
    @Column(name = "order_line_id", nullable = false, length = 100)
    var orderLineId: String = "",
    @Column(name = "stock_id", nullable = false)
    var stockId: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var quantity: Int = 0,
    @Column(name = "required_delivery_date", nullable = false)
    var requiredDeliveryDate: LocalDate = LocalDate.now(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

@Entity
@Table(name = "inbound_shipment")
class InboundShipmentEntity(
    @Id
    @Column(name = "shipment_id", length = 100)
    var shipmentId: String = ""
)

@Entity
@Table(name = "inbound_supply")
class InboundSupplyEntity(
    @Id
    @Column(name = "shipment_line_id", length = 100)
    var shipmentLineId: String = "",
    @Column(name = "shipment_id", nullable = false, length = 100)
    var shipmentId: String = "",
    @Column(name = "lot_id", nullable = false, length = 64)
    var lotId: String = UUID.randomUUID().toString(),
    @Column(nullable = false, length = 64)
    var sku: String = "",
    @Column(name = "warehouse_code", nullable = false, length = 50)
    var warehouseCode: String = "MAIN",
    @Column(name = "expected_quantity", nullable = false)
    var expectedQuantity: Int = 0,
    @Column(name = "quantity_reserved", nullable = false)
    var quantityReserved: Int = 0,
    @Column(name = "expected_arrival_date", nullable = false)
    var expectedArrivalDate: LocalDate = LocalDate.now(),
    @Column(nullable = false)
    var received: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
) {
    val quantityAvailable: Int
        get() = expectedQuantity - quantityReserved
}

@Entity
@Table(
    name = "inbound_reservation",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_inbound_reservation_order_shipment",
        columnNames = ["order_line_id", "shipment_line_id"]
    )]
)
class InboundReservationEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "order_id", nullable = false, length = 100)
    var orderId: String = "",
    @Column(name = "order_line_id", nullable = false, length = 100)
    var orderLineId: String = "",
    @Column(name = "shipment_id", nullable = false, length = 100)
    var shipmentId: String = "",
    @Column(name = "shipment_line_id", nullable = false, length = 100)
    var shipmentLineId: String = "",
    @Column(nullable = false)
    var quantity: Int = 0,
    @Column(name = "required_delivery_date", nullable = false)
    var requiredDeliveryDate: LocalDate = LocalDate.now(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

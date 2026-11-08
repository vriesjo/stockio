package nl.stockio.stock.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.job.AllocationJobTrackingState

@Entity
@Table(name = "stock_projection_lock")
class StockProjectionLockEntity(
    @Id
    var id: Int = 1
)

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
@Table(name = "allocation_job_result")
class AllocationJobResultEntity(
    @Id
    @Column(name = "job_id", nullable = false)
    var jobId: UUID = UUID.randomUUID(),
    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    var resultJson: String = ""
)

@Entity
@Table(name = "allocation_job_tracking")
class AllocationJobTrackingEntity(
    @Id
    @Column(name = "job_id", nullable = false)
    var jobId: UUID = UUID.randomUUID(),
    @Column(name = "request_id", nullable = false)
    var requestId: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var status: AllocationJobTrackingState = AllocationJobTrackingState.PUBLISHING,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
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

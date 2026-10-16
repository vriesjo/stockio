package nl.stockio.allocation.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import nl.stockio.allocation.domain.bean.AllocationRunType
import nl.stockio.allocation.domain.bean.AllocationSourceType
import nl.stockio.allocation.domain.bean.AllocationStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "allocation_run")
class AllocationRunEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "request_id", nullable = false, unique = true)
    var requestId: UUID = UUID.randomUUID(),
    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String = "",
    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false, length = 40)
    var runType: AllocationRunType = AllocationRunType.NEW_DEMAND,
    @Column(name = "as_of_date", nullable = false)
    var asOfDate: LocalDate = LocalDate.now(),
    @Column(name = "open_backorder_count", nullable = false)
    var openBackorderCount: Int = 0,
    @Column(nullable = false)
    var completed: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

@Entity
@Table(
    name = "allocation_sku_lock"
)
class AllocationSkuLockEntity(
    @Id
    @Column(length = 64)
    var sku: String = ""
)

@Entity
@Table(
    name = "allocation_sku_operation",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_allocation_sku_operation_run_sku",
        columnNames = ["run_id", "sku"]
    )]
)
class AllocationSkuOperationEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "run_id", nullable = false)
    var runId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 64)
    var sku: String = "",
    @Column(name = "request_hash", nullable = false, length = 64)
    var requestHash: String = "",
    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    var resultJson: String = ""
)

@Entity
@Table(name = "allocation_order_claim")
class AllocationOrderClaimEntity(
    @Id
    @Column(name = "order_id", length = 100)
    var orderId: String = "",
    @Column(name = "run_id", nullable = false)
    var runId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 64)
    var sku: String = ""
)

@Entity
@Table(name = "allocation_record")
class AllocationRecordEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "run_id", nullable = false)
    var runId: UUID = UUID.randomUUID(),
    @Column(name = "sequence_number", nullable = false)
    var sequenceNumber: Int = 0,
    @Column(name = "order_id", nullable = false, length = 100)
    var orderId: String = "",
    @Column(nullable = false, length = 200)
    var customer: String = "",
    @Column(nullable = false, length = 100)
    var sku: String = "",
    @Column(nullable = false)
    var priority: Int = 1,
    @Column(name = "placed_date", nullable = false)
    var placedDate: LocalDate = LocalDate.now(),
    @Column(name = "required_delivery_date", nullable = false)
    var requiredDeliveryDate: LocalDate = LocalDate.now(),
    @Column(name = "requested_quantity", nullable = false)
    var requestedQuantity: Int = 0,
    @Column(name = "allocated_from_inventory", nullable = false)
    var allocatedFromInventory: Int = 0,
    @Column(name = "allocated_from_inbound", nullable = false)
    var allocatedFromInbound: Int = 0,
    @Column(name = "backordered_quantity", nullable = false)
    var backorderedQuantity: Int = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var status: AllocationStatus = AllocationStatus.BACKORDERED
)

@Entity
@Table(name = "allocation_source")
class AllocationSourceEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "allocation_record_id", nullable = false)
    var allocationRecordId: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    var sourceType: AllocationSourceType = AllocationSourceType.INVENTORY,
    @Column(name = "source_id", nullable = false, length = 100)
    var sourceId: String = "",
    @Column(nullable = false)
    var quantity: Int = 0,
    @Column(name = "source_date", nullable = false)
    var sourceDate: LocalDate = LocalDate.now(),
    @Column(name = "warehouse_location", length = 100)
    var warehouseLocation: String? = null
)

@Entity
@Table(name = "open_backorder")
class OpenBackorderEntity(
    @Id
    @Column(name = "order_id", length = 100)
    var orderId: String = "",
    @Column(nullable = false, length = 200)
    var customer: String = "",
    @Column(nullable = false, length = 100)
    var sku: String = "",
    @Column(nullable = false)
    var priority: Int = 1,
    @Column(name = "placed_date", nullable = false)
    var placedDate: LocalDate = LocalDate.now(),
    @Column(name = "required_delivery_date", nullable = false)
    var requiredDeliveryDate: LocalDate = LocalDate.now(),
    @Column(name = "original_quantity", nullable = false)
    var originalQuantity: Int = 0,
    @Column(name = "remaining_quantity", nullable = false)
    var remainingQuantity: Int = 0,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

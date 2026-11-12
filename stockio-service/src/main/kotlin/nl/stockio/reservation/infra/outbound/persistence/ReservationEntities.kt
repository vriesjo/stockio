package nl.stockio.reservation.infra

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

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

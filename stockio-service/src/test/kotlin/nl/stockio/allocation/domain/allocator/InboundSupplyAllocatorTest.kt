package nl.stockio.allocation.domain.allocator

import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.model.InboundReservationChange
import nl.stockio.allocation.domain.model.InboundSupplyState
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationSourceType
import org.junit.jupiter.api.Test

class InboundSupplyAllocatorTest {
    private val allocator = InboundSupplyAllocator()
    private val latestArrivalDate = LocalDate.of(2026, 11, 10)
    private val deliveryDate = LocalDate.of(2026, 11, 12)

    @Test
    fun `allocates eligible supply in order and updates remaining quantities and sources`() {
        val lateSupply = supply("SHIP-LATE", "LINE-LATE", latestArrivalDate.plusDays(1), 8, 0)
        val firstEligible = supply("SHIP-1", "LINE-1", latestArrivalDate.minusDays(2), 5, 3)
        val secondEligible = supply("SHIP-2", "LINE-2", latestArrivalDate, 4, 0)
        val remainingByShipment = mutableMapOf(
            "LINE-LATE" to 8,
            "LINE-1" to 2,
            "LINE-2" to 4
        )
        val reservations = mutableMapOf<Pair<String, String>, InboundReservationChange>()
        val sources = mutableListOf<AllocationSource>()

        val allocated = allocator.allocate(
            sku = "SKU",
            latestArrivalDate = latestArrivalDate,
            requested = 8,
            shipmentsBySku = mapOf("SKU" to listOf(lateSupply, firstEligible, secondEligible)),
            remainingByShipment = remainingByShipment,
            sources = sources,
            orderId = "ORDER",
            orderLineId = "ORDER-LINE",
            deliveryDate = deliveryDate,
            reservationsByOrderAndShipment = reservations
        )

        allocated shouldBe 6
        remainingByShipment shouldBe mapOf("LINE-LATE" to 8, "LINE-1" to 0, "LINE-2" to 0)
        lateSupply.quantityReserved shouldBe 0
        firstEligible.quantityReserved shouldBe 5
        secondEligible.quantityReserved shouldBe 4
        reservations.values.map { it.shipmentLineId to it.quantity } shouldBe listOf(
            "LINE-1" to 2,
            "LINE-2" to 4
        )
        reservations.values.map { it.requiredDeliveryDate }.distinct() shouldBe listOf(deliveryDate)
        sources.map { it.sourceType } shouldBe listOf(AllocationSourceType.INBOUND, AllocationSourceType.INBOUND)
        sources.map { it.sourceId to it.quantity } shouldBe listOf("LINE-1" to 2, "LINE-2" to 4)
        sources.map { it.sourceDate } shouldBe listOf(firstEligible.expectedArrivalDate, secondEligible.expectedArrivalDate)
    }

    @Test
    fun `adds allocation to an existing reservation and updates its delivery date`() {
        val existingReservationId = UUID.randomUUID()
        val existingReservation = InboundReservationChange(
            id = existingReservationId,
            orderId = "ORDER",
            orderLineId = "ORDER-LINE",
            shipmentId = "SHIP",
            shipmentLineId = "SHIP-LINE",
            quantity = 2,
            requiredDeliveryDate = deliveryDate.minusDays(1),
            createdAt = Instant.parse("2026-11-01T10:00:00Z")
        )
        val reservations = mutableMapOf(("ORDER-LINE" to "SHIP-LINE") to existingReservation)
        val shipment = supply("SHIP", "SHIP-LINE", latestArrivalDate, 10, 4)

        val allocated = allocator.allocate(
            sku = "SKU",
            latestArrivalDate = latestArrivalDate,
            requested = 3,
            shipmentsBySku = mapOf("SKU" to listOf(shipment)),
            remainingByShipment = mutableMapOf("SHIP-LINE" to 6),
            sources = mutableListOf(),
            orderId = "ORDER",
            orderLineId = "ORDER-LINE",
            deliveryDate = deliveryDate,
            reservationsByOrderAndShipment = reservations
        )

        allocated shouldBe 3
        reservations.size shouldBe 1
        existingReservation.quantity shouldBe 5
        existingReservation.requiredDeliveryDate shouldBe deliveryDate
        existingReservation.id shouldBe existingReservationId
        existingReservation.createdAt shouldBe Instant.parse("2026-11-01T10:00:00Z")
    }

    @Test
    fun `returns zero when no shipment is eligible or the sku has no supply`() {
        val shipment = supply("SHIP", "SHIP-LINE", latestArrivalDate.plusDays(1), 10, 0)
        val remainingByShipment = mutableMapOf("SHIP-LINE" to 10)
        val sources = mutableListOf<AllocationSource>()
        val reservations = mutableMapOf<Pair<String, String>, InboundReservationChange>()
        val suppliesBySku = mapOf("SKU" to listOf(shipment))

        fun allocate(sku: String, requested: Int) = allocator.allocate(
            sku = sku,
            latestArrivalDate = latestArrivalDate,
            requested = requested,
            shipmentsBySku = suppliesBySku,
            remainingByShipment = remainingByShipment,
            sources = sources,
            orderId = "ORDER",
            orderLineId = "ORDER-LINE",
            deliveryDate = deliveryDate,
            reservationsByOrderAndShipment = reservations
        )

        allocate("SKU", 4) shouldBe 0
        allocate("MISSING-SKU", 4) shouldBe 0
        allocate("SKU", 0) shouldBe 0
        remainingByShipment shouldBe mapOf("SHIP-LINE" to 10)
        shipment.quantityReserved shouldBe 0
        sources shouldBe emptyList()
        reservations shouldBe emptyMap()
    }

    private fun supply(
        shipmentId: String,
        shipmentLineId: String,
        arrivalDate: LocalDate,
        expectedQuantity: Int,
        quantityReserved: Int
    ) = InboundSupplyState(
        shipmentId = shipmentId,
        shipmentLineId = shipmentLineId,
        sku = "SKU",
        warehouseCode = "WAREHOUSE",
        expectedQuantity = expectedQuantity,
        expectedArrivalDate = arrivalDate,
        quantityReserved = quantityReserved
    )
}

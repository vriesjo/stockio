package nl.stockio.allocation.domain.processor.allocator

import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.model.InventoryLotState
import nl.stockio.allocation.domain.model.StockReservationChange
import nl.stockio.allocation.domain.processor.allocator.InventoryLotAllocator
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationSourceType
import org.junit.jupiter.api.Test

class InventoryLotAllocatorTest {
    private val allocator = InventoryLotAllocator()
    private val deliveryDate = LocalDate.of(2026, 11, 12)

    @Test
    fun `allocates available inventory in lot order and records reservations and sources`() {
        val firstLot = lot("LOT-1", quantityOnHand = 5, quantityReserved = 3)
        val secondLot = lot("LOT-2", quantityOnHand = 4, quantityReserved = 0)
        val exhaustedLot = lot("LOT-3", quantityOnHand = 5, quantityReserved = 5)
        val reservations = mutableMapOf<Pair<String, UUID>, StockReservationChange>()
        val sources = mutableListOf<AllocationSource>()

        val allocated = allocator.allocate(
            sku = "SKU",
            requested = 8,
            lotsBySku = mapOf("SKU" to listOf(firstLot, secondLot, exhaustedLot)),
            sources = sources,
            orderId = "ORDER",
            orderLineId = "ORDER-LINE",
            reservationsByOrderAndStock = reservations,
            deliveryDate = deliveryDate
        )

        allocated shouldBe 6
        firstLot.quantityReserved shouldBe 5
        secondLot.quantityReserved shouldBe 4
        exhaustedLot.quantityReserved shouldBe 5
        reservations.values.map { it.stockId to it.quantity } shouldBe listOf(
            firstLot.stockId to 2,
            secondLot.stockId to 4
        )
        reservations.values.map { it.orderId to it.orderLineId }.distinct() shouldBe
            listOf("ORDER" to "ORDER-LINE")
        reservations.values.map { it.requiredDeliveryDate }.distinct() shouldBe listOf(deliveryDate)
        sources.map { it.sourceType } shouldBe listOf(AllocationSourceType.INVENTORY, AllocationSourceType.INVENTORY)
        sources.map { it.sourceId to it.quantity } shouldBe listOf("LOT-1" to 2, "LOT-2" to 4)
        sources.map { it.sourceDate } shouldBe listOf(firstLot.receivedDate, secondLot.receivedDate)
        sources.map { it.warehouseLocation }.distinct() shouldBe listOf("WAREHOUSE")
    }

    @Test
    fun `adds allocation to an existing reservation and updates its delivery date`() {
        val stockId = UUID.randomUUID()
        val existingReservationId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-11-01T10:00:00Z")
        val existingReservation = StockReservationChange(
            id = existingReservationId,
            orderId = "ORDER",
            orderLineId = "ORDER-LINE",
            stockId = stockId,
            quantity = 2,
            requiredDeliveryDate = deliveryDate.minusDays(1),
            createdAt = createdAt
        )
        val reservations = mutableMapOf(("ORDER-LINE" to stockId) to existingReservation)
        val inventoryLot = lot("LOT", quantityOnHand = 10, quantityReserved = 1, stockId = stockId)

        val allocated = allocator.allocate(
            sku = "SKU",
            requested = 3,
            lotsBySku = mapOf("SKU" to listOf(inventoryLot)),
            sources = mutableListOf(),
            orderId = "ORDER",
            orderLineId = "ORDER-LINE",
            reservationsByOrderAndStock = reservations,
            deliveryDate = deliveryDate
        )

        allocated shouldBe 3
        reservations.size shouldBe 1
        existingReservation.quantity shouldBe 5
        existingReservation.requiredDeliveryDate shouldBe deliveryDate
        existingReservation.id shouldBe existingReservationId
        existingReservation.createdAt shouldBe createdAt
        inventoryLot.quantityReserved shouldBe 4
    }

    @Test
    fun `returns zero when no matching sku has available inventory`() {
        val otherSkuLot = lot("OTHER", sku = "OTHER-SKU", quantityOnHand = 10, quantityReserved = 0)
        val exhaustedLot = lot("EXHAUSTED", quantityOnHand = 4, quantityReserved = 4)
        val reservations = mutableMapOf<Pair<String, UUID>, StockReservationChange>()
        val sources = mutableListOf<AllocationSource>()
        val lotsBySku = mapOf(
            "OTHER-SKU" to listOf(otherSkuLot),
            "SKU" to listOf(exhaustedLot)
        )

        allocator.allocate("SKU", 3, lotsBySku, sources, "ORDER", "ORDER-LINE", reservations, deliveryDate) shouldBe 0
        allocator.allocate("MISSING-SKU", 3, lotsBySku, sources, "ORDER", "ORDER-LINE", reservations, deliveryDate) shouldBe 0
        allocator.allocate("SKU", 0, lotsBySku, sources, "ORDER", "ORDER-LINE", reservations, deliveryDate) shouldBe 0

        otherSkuLot.quantityReserved shouldBe 0
        exhaustedLot.quantityReserved shouldBe 4
        reservations shouldBe emptyMap()
        sources shouldBe emptyList()
    }

    private fun lot(
        lotId: String,
        sku: String = "SKU",
        quantityOnHand: Int,
        quantityReserved: Int,
        stockId: UUID = UUID.randomUUID()
    ) = InventoryLotState(
        stockId = stockId,
        sku = sku,
        lotId = lotId,
        warehouseCode = "WAREHOUSE",
        receivedDate = LocalDate.of(2026, 11, 5),
        quantityOnHand = quantityOnHand,
        quantityReserved = quantityReserved
    )
}

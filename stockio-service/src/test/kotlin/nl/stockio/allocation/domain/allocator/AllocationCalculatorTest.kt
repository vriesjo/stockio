package nl.stockio.allocation.domain.allocator

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.model.InboundSupplyState
import nl.stockio.allocation.domain.model.InventoryLotState
import nl.stockio.api.dto.AllocationSource
import nl.stockio.api.dto.AllocationSourceType
import nl.stockio.api.dto.AllocationStatus
import org.junit.jupiter.api.Test

class AllocationCalculatorTest {
    private val asOfDate = LocalDate.of(2026, 11, 6)
    private val inventoryLotAllocator = mockk<InventoryLotAllocator>()
    private val inboundSupplyAllocator = mockk<InboundSupplyAllocator>()
    private val calculator = AllocationCalculator(inventoryLotAllocator, inboundSupplyAllocator)

    @Test
    fun `allocates orders by priority and placed date and registers remaining backorders`() {
        val inventoryLines = mutableListOf<String>()
        val inboundLines = mutableListOf<String>()
        every {
            inventoryLotAllocator.allocate(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val lineId = arg<String>(5)
            inventoryLines += lineId
            val allocated = mapOf("line-c" to 1, "line-a" to 2).getOrDefault(lineId, 0)
            if (allocated > 0) {
                arg<MutableList<AllocationSource>>(3) += source(AllocationSourceType.INVENTORY, "lot-$lineId", allocated)
            }
            allocated
        }
        every {
            inboundSupplyAllocator.allocate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val lineId = arg<String>(7)
            inboundLines += lineId
            val allocated = if (lineId == "line-a") 1 else 0
            if (allocated > 0) {
                arg<MutableList<AllocationSource>>(5) += source(AllocationSourceType.INBOUND, "shipment-$lineId", allocated)
            }
            allocated
        }

        val result = calculator.calculate(
            orders = listOf(
                order("line-b", priority = 2, placedDate = asOfDate.minusDays(2), quantity = 2),
                order("line-a", priority = 1, placedDate = asOfDate.minusDays(1), quantity = 4),
                order("line-c", priority = 1, placedDate = asOfDate.minusDays(2), quantity = 1)
            ),
            asOfDate = asOfDate,
            inventory = emptyList(),
            inboundSupply = emptyList(),
            stockReservations = emptyList(),
            inboundReservations = emptyList(),
            registerBackorders = true
        )

        inventoryLines shouldBe listOf("line-c", "line-a", "line-b")
        inboundLines shouldBe listOf("line-c", "line-a", "line-b")
        result.allocations.map { it.orderLineId } shouldBe listOf("line-c", "line-a", "line-b")
        result.allocations.map { it.status } shouldBe listOf(
            AllocationStatus.ALLOCATED,
            AllocationStatus.PARTIALLY_BACKORDERED,
            AllocationStatus.BACKORDERED
        )
        result.allocations.map { it.backorderedQuantity } shouldBe listOf(0, 1, 2)
        result.allocations[1].sources.map { it.sourceType } shouldBe listOf(
            AllocationSourceType.INVENTORY,
            AllocationSourceType.INBOUND
        )
        result.backordersToRegister.map { it.orderLineId to it.quantity } shouldBe listOf(
            "line-a" to 1,
            "line-b" to 2
        )
    }

    @Test
    fun `sorts copied inventory and inbound state without mutating input state`() {
        val olderInventory = inventoryLot("lot-a", asOfDate.minusDays(2))
        val newerInventory = inventoryLot("lot-b", asOfDate.minusDays(1))
        val laterSupply = inboundSupply("shipment-b", asOfDate.plusDays(2))
        val earlierSupply = inboundSupply("shipment-a", asOfDate.plusDays(1))
        var observedInventory = emptyList<InventoryLotState>()
        var observedSupply = emptyList<InboundSupplyState>()
        every {
            inventoryLotAllocator.allocate(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            observedInventory = arg<Map<String, List<InventoryLotState>>>(2).getValue("SKU")
            observedInventory.first().quantityReserved += 1
            1
        }
        every {
            inboundSupplyAllocator.allocate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            observedSupply = arg<Map<String, List<InboundSupplyState>>>(3).getValue("SKU")
            observedSupply.first().quantityReserved += 1
            1
        }

        val result = calculator.calculate(
            orders = listOf(
                order(
                    "line",
                    sku = "SKU",
                    quantity = 2,
                    requiredDeliveryDate = asOfDate.plusDays(3)
                )
            ),
            asOfDate = asOfDate,
            inventory = listOf(newerInventory, olderInventory),
            inboundSupply = listOf(laterSupply, earlierSupply),
            stockReservations = emptyList(),
            inboundReservations = emptyList(),
            registerBackorders = false
        )

        observedInventory.map { it.lotId } shouldBe listOf("lot-a", "lot-b")
        observedSupply.map { it.shipmentLineId } shouldBe listOf("shipment-a", "shipment-b")
        olderInventory.quantityReserved shouldBe 0
        earlierSupply.quantityReserved shouldBe 0
        result.inventoryLots.first { it.lotId == "lot-a" }.quantityReserved shouldBe 1
        result.inboundSupplies.first { it.shipmentLineId == "shipment-a" }.quantityReserved shouldBe 1
        result.backordersToRegister shouldBe emptyList()
    }

    private fun order(
        lineId: String,
        priority: Int = 1,
        placedDate: LocalDate = asOfDate,
        sku: String = "SKU",
        quantity: Int,
        requiredDeliveryDate: LocalDate = asOfDate.minusDays(1)
    ) = AllocationOrder(
        orderId = "order-$lineId",
        orderLineId = lineId,
        customer = "customer",
        sku = sku,
        quantity = quantity,
        priority = priority,
        placedDate = placedDate,
        requiredDeliveryDate = requiredDeliveryDate
    )

    private fun inventoryLot(lotId: String, receivedDate: LocalDate) = InventoryLotState(
        stockId = UUID.randomUUID(),
        sku = "SKU",
        lotId = lotId,
        warehouseCode = "warehouse",
        receivedDate = receivedDate,
        quantityOnHand = 10,
        quantityReserved = 0
    )

    private fun inboundSupply(shipmentLineId: String, arrivalDate: LocalDate) = InboundSupplyState(
        shipmentId = "shipment-$shipmentLineId",
        shipmentLineId = shipmentLineId,
        sku = "SKU",
        warehouseCode = "warehouse",
        expectedQuantity = 10,
        expectedArrivalDate = arrivalDate,
        quantityReserved = 0
    )

    private fun source(type: AllocationSourceType, id: String, quantity: Int) = AllocationSource(
        sourceType = type,
        sourceId = id,
        quantity = quantity,
        sourceDate = asOfDate
    )
}

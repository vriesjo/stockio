package nl.stockio.allocation.application.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import nl.stockio.allocation.application.service.InboundShipmentRegistrationService
import nl.stockio.api.dto.InboundShipment
import nl.stockio.api.dto.InboundShipmentLine
import nl.stockio.reservation.domain.port.InboundShipmentRepository
import nl.stockio.reservation.domain.port.InboundSupplyRepository
import nl.stockio.reservation.infra.InboundShipmentEntity
import nl.stockio.reservation.infra.InboundSupplyEntity
import org.junit.jupiter.api.Test

class InboundShipmentRegistrationServiceTest {
    private val shipments = mockk<InboundShipmentRepository>()
    private val supplies = mockk<InboundSupplyRepository>()
    private val service = InboundShipmentRegistrationService(shipments, supplies)
    private val today = LocalDate.of(2026, 11, 9)
    private val line = InboundShipmentLine("line-1", "SKU", 5, today, "EAST")

    @Test
    fun `registers a new shipment and its lines`() {
        val savedShipment = slot<InboundShipmentEntity>()
        val savedSupplies = slot<Iterable<InboundSupplyEntity>>()
        every { shipments.existsById("ship-1") } returns false
        every { supplies.lockAllByShipmentIdIn(listOf("ship-1")) } returns emptyList()
        every { supplies.findAllById(listOf("line-1")) } returns emptyList()
        every { shipments.saveAndFlush(capture(savedShipment)) } answers { firstArg() }
        every { supplies.saveAll(capture(savedSupplies)) } answers { firstArg() }

        service.registerInboundSupply(listOf(InboundShipment("ship-1", listOf(line))))

        savedShipment.captured.shipmentId shouldBe "ship-1"
        val supply = savedSupplies.captured.single()
        supply.shipmentId shouldBe "ship-1"
        supply.shipmentLineId shouldBe "line-1"
        supply.sku shouldBe "SKU"
        supply.warehouseCode shouldBe "EAST"
        supply.expectedQuantity shouldBe 5
        supply.expectedArrivalDate shouldBe today
    }

    @Test
    fun `rejects a line id that already belongs to another shipment`() {
        every { shipments.existsById("ship-1") } returns false
        every { supplies.lockAllByShipmentIdIn(listOf("ship-1")) } returns emptyList()
        every { supplies.findAllById(listOf("line-1")) } returns listOf(
            InboundSupplyEntity(shipmentLineId = "line-1", shipmentId = "other-ship", sku = "SKU")
        )

        shouldThrow<IllegalArgumentException> {
            service.registerInboundSupply(listOf(InboundShipment("ship-1", listOf(line))))
        }.message shouldBe "Shipment line IDs already belong to another shipment."

        verify(exactly = 0) { shipments.saveAndFlush(any()) }
        verify(exactly = 0) { supplies.saveAll(any<Iterable<InboundSupplyEntity>>()) }
    }

    @Test
    fun `rejects a change of sku warehouse or arrival date`() {
        val existing = existingSupply()
        every { shipments.existsById("ship-1") } returns true
        every { supplies.lockAllByShipmentIdIn(listOf("ship-1")) } returns listOf(existing)
        val changes = listOf(
            line.copy(sku = "OTHER"),
            line.copy(warehouseCode = "WEST"),
            line.copy(expectedArrivalDate = today.plusDays(1))
        )

        changes.forEach { changed ->
            shouldThrow<IllegalArgumentException> {
                service.registerInboundSupply(listOf(InboundShipment("ship-1", listOf(changed))))
            }.message shouldBe "Shipment line 'line-1' has different SKU, warehouse, or arrival date."
        }

        verify(exactly = 0) { supplies.saveAll(any<Iterable<InboundSupplyEntity>>()) }
    }

    @Test
    fun `rejects an expected quantity below the reserved quantity`() {
        every { shipments.existsById("ship-1") } returns true
        every { supplies.lockAllByShipmentIdIn(listOf("ship-1")) } returns listOf(existingSupply(quantityReserved = 4))

        shouldThrow<IllegalArgumentException> {
            service.registerInboundSupply(listOf(InboundShipment("ship-1", listOf(line.copy(expectedQuantity = 3)))))
        }.message shouldBe "Shipment line 'line-1' cannot change received quantity or fall below reservations."

        verify(exactly = 0) { supplies.saveAll(any<Iterable<InboundSupplyEntity>>()) }
    }

    private fun existingSupply(quantityReserved: Int = 0) = InboundSupplyEntity(
        shipmentLineId = "line-1", shipmentId = "ship-1", sku = "SKU", warehouseCode = "EAST",
        expectedQuantity = 5, quantityReserved = quantityReserved, expectedArrivalDate = today, received = false
    )
}

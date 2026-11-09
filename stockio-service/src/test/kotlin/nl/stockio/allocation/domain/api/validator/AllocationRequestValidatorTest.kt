package nl.stockio.allocation.domain.api.validator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import nl.stockio.api.dto.InboundShipment
import nl.stockio.api.dto.InboundShipmentLine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AllocationRequestValidatorTest {
    private val beanValidator = mockk<Validator>()
    private val validator = AllocationRequestValidator(beanValidator)
    private val today = LocalDate.of(2026, 11, 9)

    @BeforeEach
    fun acceptBeans() {
        every { beanValidator.validate(any<Any>(), *anyVararg()) } returns emptySet()
    }

    @Test
    fun `rejects a placed date after the required delivery date`() {
        val request = request(orders = listOf(order("order-1", placed = today, delivery = today.minusDays(1))))

        shouldThrow<AllocationRequestException> { validator.validate(request) }
            .message shouldBe "An order's placed date must be on or before its required delivery date."
    }

    @Test
    fun `rejects duplicate demand order shipment and line ids`() {
        val cases = listOf(
            request(orders = listOf(order("same"), order("same", lineId = "other-line"))) to
                "Demand order IDs must be unique within a request.",
            request(orders = listOf(order("order-1", lineId = "same"), order("order-2", lineId = "same"))) to
                "Demand order line IDs must be globally unique within a request.",
            request(shipments = listOf(shipment("same"), shipment("same", lineId = "other-line"))) to
                "Inbound shipment IDs must be unique within a request.",
            request(shipments = listOf(shipment("ship-1", lineId = "same"), shipment("ship-2", lineId = "same"))) to
                "Inbound shipment line IDs must be globally unique within a request."
        )

        cases.forEach { (invalid, message) ->
            shouldThrow<AllocationRequestException> { validator.validate(invalid) }.message shouldBe message
        }
    }

    @Test
    fun `rejects bean violations before the custom identity rules`() {
        val violation = mockk<ConstraintViolation<AllocationRequest>>()
        every { violation.propertyPath } returns mockk(relaxed = true)
        every { violation.message } returns "must not be blank"
        every { beanValidator.validate(any<AllocationRequest>(), *anyVararg()) } returns setOf(violation)

        shouldThrow<AllocationRequestException> { validator.validate(request(orders = listOf(order("same"), order("same")))) }
            .message shouldBe "Validate of bean AllocationRequest failed"
    }

    @Test
    fun `backorder runs skip demand order checks and use the backorder shipment message`() {
        val demand = listOf(
            order("same", placed = today.plusDays(1), delivery = today, lineId = "same"),
            order("same", placed = today, lineId = "same")
        )
        validator.validate(request(AllocationRunType.BACKORDER_FULFILLMENT, orders = demand))

        shouldThrow<AllocationRequestException> {
            validator.validate(request(
                AllocationRunType.BACKORDER_FULFILLMENT,
                shipments = listOf(shipment("same"), shipment("same", lineId = "other-line"))
            ))
        }.message shouldBe "Inbound shipment IDs must be unique within a backorder request."
    }

    private fun request(
        runType: AllocationRunType = AllocationRunType.NEW_DEMAND,
        orders: List<DemandOrder> = listOf(order("order-1")),
        shipments: List<InboundShipment> = listOf(shipment("ship-1"))
    ) = AllocationRequest(UUID.randomUUID(), today, runType, orders, shipments)

    private fun order(
        id: String,
        placed: LocalDate = today,
        delivery: LocalDate = today,
        lineId: String = "$id-line"
    ) = DemandOrder(id, "customer", 1, placed, listOf(DemandOrderLine(lineId, "SKU", 1, delivery)))

    private fun shipment(id: String, lineId: String = "$id-line") =
        InboundShipment(id, listOf(InboundShipmentLine(lineId, "SKU", 1, today)))
}

package nl.stockio.allocation.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.application.service.AllocationRequestLimits
import nl.stockio.allocation.application.service.AllocationRequestTooLargeException
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.api.dto.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AllocationRequestLimitsTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val properties = AllocationAdmissionProperties(true, 1, 2, 2, 3, 3, 2, 4096)
    private val today = LocalDate.now()
    private fun line(sku: String = "A") = DemandOrderLine(UUID.randomUUID().toString(), sku, 1, today)
    private fun order(lines: List<DemandOrderLine>) =
        DemandOrder(UUID.randomUUID().toString(), "customer", 1, today, lines)
    private fun shipment(lines: Int, sku: String = "A") = InboundShipment(UUID.randomUUID().toString(),
        List(lines) { InboundShipmentLine(UUID.randomUUID().toString(), sku, 1, today) })
    private fun request(orders: List<DemandOrder> = emptyList(), shipments: List<InboundShipment> = emptyList()) =
        AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND, orders, shipments)
    private fun limits(properties: AllocationAdmissionProperties = this.properties) =
        AllocationRequestLimits(properties, mapper, SimpleMeterRegistry())

    @Test
    fun `accepts exact aggregate counts and rejects counts across multiple documents`() {
        limits().validate(request(listOf(order(listOf(line(), line())), order(listOf(line()))),
            listOf(shipment(2), shipment(1))))
        val cases = listOf(
            request(List(3) { order(emptyList()) }),
            request(shipments = List(3) { shipment(0) }),
            request(listOf(order(listOf(line(), line())), order(listOf(line(), line())))),
            request(shipments = listOf(shipment(2), shipment(2))),
            request(listOf(order(listOf(line("A"), line("B")))), listOf(shipment(1, "C")))
        )
        cases.forEach { request ->
            assertThrows(IllegalArgumentException::class.java) { limits().validate(request) }
        }
    }

    @Test
    fun `counts explicit internal scope with inbound skus before background execution`() {
        val command = BackorderAllocationRequest(UUID.randomUUID(), today,
            BackorderAllocationTrigger.STOCK_LEDGER_RECEIPT, setOf("A", "B"))
        limits().validate(command)
        assertThrows(IllegalArgumentException::class.java) {
            limits().validate(command.copy(inboundShipments = listOf(shipment(1, "C"))))
        }
    }

    @Test
    fun `bounds canonical UTF8 payload bytes including requests already deserialized by MCP`() {
        val request = request(listOf(order(listOf(line())).copy(customer = "caf\u00e9")))
        val bytes = mapper.writeValueAsBytes(request).size
        limits(properties.copy(maxRequestBytes = bytes)).validate(request)
        assertThrows(AllocationRequestTooLargeException::class.java) {
            limits(properties.copy(maxRequestBytes = bytes - 1)).validate(request)
        }
        assertDoesNotThrow { limits(AllocationAdmissionProperties()).validate(request(List(3) { order(emptyList()) })) }
    }
}

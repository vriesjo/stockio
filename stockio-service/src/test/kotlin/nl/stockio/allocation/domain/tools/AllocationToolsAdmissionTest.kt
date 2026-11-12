package nl.stockio.allocation.domain.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.allocation.domain.tools.AllocationMcpTools
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.DemandOrder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AllocationToolsAdmissionTest {
    private val processor = mockk<AllocationProcessor>()
    private val limits = AllocationRequestLimits(AllocationAdmissionProperties(true, 1, 1, 1, 1, 1, 1, 4096),
        jacksonObjectMapper().findAndRegisterModules(), SimpleMeterRegistry())
    private val tools = AllocationMcpTools(processor, mockk(), mockk(), mockk(), mockk(), limits, "reports")

    @Test
    fun `MCP retains the typed capacity error and unchanged retry guidance`() {
        val request = AllocationRequest(UUID.randomUUID(), LocalDate.now(), AllocationRunType.NEW_DEMAND)
        every { processor.processSync(request) } throws AllocationCapacityExceededException()
        val exception = assertThrows(AllocationCapacityExceededException::class.java) {
            tools.allocateAndStockProject(request)
        }
        assertTrue(exception.message.orEmpty().contains("ALLOCATION_CAPACITY_EXHAUSTED"))
        assertTrue(exception.message.orEmpty().contains("retry unchanged"))
    }

    @Test
    fun `manual backorder mapping cannot discard oversized input before validating its shape`() {
        val today = LocalDate.now()
        val order = DemandOrder("O", "C", 1, today, emptyList())
        val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.BACKORDER_FULFILLMENT,
            listOf(order, order.copy(orderId = "O2")))
        assertThrows(IllegalArgumentException::class.java) {
            tools.allocateOpenBackordersByInventoryAndInboundSupply(request)
        }
        verify(exactly = 0) { processor.processSync(any<BackorderAllocationRequest>()) }
    }
}

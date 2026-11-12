package nl.stockio.allocation.domain.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.validator.AllocationRequestValidator
import nl.stockio.allocation.domain.processor.SkuAllocationProcessor
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.AllocationStatus
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.DemandOrderLine
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class AllocationServiceTest {
    private val validator = mockk<AllocationRequestValidator>()
    private val lifecycle = mockk<AllocationRunLifecycleService>()
    private val processor = mockk<SkuAllocationProcessor>()
    private val documents = mockk<AllocationDocumentService>()
    private val service = AllocationService(validator, lifecycle, processor, documents, mockk(), 2)
    private val today = LocalDate.of(2026, 11, 9)
    private val requestId = UUID.randomUUID()

    @Test
    fun `returns a completed run before validating the request`() {
        val request = request()
        val completed = result(request)
        every { lifecycle.getRun(request, any()) } returns completed

        service.allocation(request).block() shouldBe completed

        verify { validator wasNot Called }
        verify { documents wasNot Called }
        verify { processor wasNot Called }
    }

    @Test
    fun `fails the allocation when one sku worker fails`() {
        val request = request(lines = listOf(line("late", "SKU-B"), line("early", "SKU-A")))
        val run = openRun(request)
        every { lifecycle.getRun(request, any()) } returns null
        every { validator.validate(request) } just Runs
        every { lifecycle.beginOrResume(request, any()) } returns run
        every { documents.initialize(run.id, request) } just Runs
        every { processor.process(run.id, any(), "SKU-A", today, any()) } returns
            Mono.error(IllegalStateException("SKU failure"))
        every { processor.process(run.id, any(), "SKU-B", today, any()) } returns Mono.just(emptyList())

        shouldThrow<IllegalStateException> { service.allocation(request).block() }.message shouldBe "SKU failure"

        verify(exactly = 0) { lifecycle.completeRun(any(), any(), any()) }
    }

    @Test
    fun `sorts records by priority then placed date then input order`() {
        val early = today
        val later = today.plusDays(2)
        val request = request(lines = listOf(
            line("L-late", "SKU"),
            line("L-high", "SKU"),
            line("L-early", "SKU"),
            line("L-newer", "SKU")
        ))
        val run = openRun(request)
        val records = listOf(
            record("L-newer", priority = 2, placed = later),
            record("L-early", priority = 2, placed = early),
            record("L-late", priority = 2, placed = early),
            record("L-high", priority = 1, placed = later)
        )
        val saved = slot<List<AllocationRecord>>()
        every { lifecycle.getRun(request, any()) } returns null
        every { validator.validate(request) } just Runs
        every { lifecycle.beginOrResume(request, any()) } returns run
        every { documents.initialize(run.id, request) } just Runs
        every { processor.process(run.id, any(), "SKU", today, any()) } returns Mono.just(records)
        every { lifecycle.completeRun(run.id, setOf("SKU"), capture(saved)) } answers { result(request) }

        service.allocation(request).block()

        saved.captured.map { it.orderLineId } shouldBe listOf("L-high", "L-late", "L-early", "L-newer")
    }

    private fun request(lines: List<DemandOrderLine> = listOf(line("line-1", "SKU"))) = AllocationRequest(
        requestId = requestId,
        asOfDate = today,
        runType = AllocationRunType.NEW_DEMAND,
        demandOrders = lines.map { demand ->
            DemandOrder(demand.orderLineId, "customer", 1, today, listOf(demand))
        }
    )

    private fun line(id: String, sku: String) = DemandOrderLine(id, sku, 1, today)

    private fun openRun(request: AllocationRequest) = AllocationRunEntity(
        id = UUID.randomUUID(),
        requestId = request.requestId,
        runType = request.runType,
        asOfDate = request.asOfDate,
        completed = false
    )

    private fun result(request: AllocationRequest) = AllocationRunResult(
        runId = UUID.randomUUID(),
        requestId = request.requestId,
        runType = request.runType,
        asOfDate = request.asOfDate,
        createdAt = Instant.parse("2026-11-09T12:00:00Z"),
        openBackorderCount = 0,
        allocations = emptyList()
    )

    private fun record(lineId: String, priority: Int, placed: LocalDate) = AllocationRecord(
        orderId = lineId,
        orderLineId = lineId,
        customer = "customer",
        sku = "SKU",
        priority = priority,
        placedDate = placed,
        requiredDeliveryDate = placed,
        requestedQuantity = 1,
        allocatedFromInventory = 1,
        allocatedFromInbound = 0,
        backorderedQuantity = 0,
        status = AllocationStatus.ALLOCATED,
        sources = emptyList()
    )
}

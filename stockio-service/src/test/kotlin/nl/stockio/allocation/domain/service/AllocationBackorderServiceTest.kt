package nl.stockio.allocation.domain.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.allocation.domain.port.OpenBackorderRepository
import nl.stockio.allocation.domain.processor.SkuAllocationProcessor
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class AllocationBackorderServiceTest {
    private val lifecycle = mockk<AllocationRunLifecycleService>()
    private val processor = mockk<SkuAllocationProcessor>()
    private val documents = mockk<AllocationDocumentService>()
    private val backorders = mockk<OpenBackorderRepository>()
    private val service = AllocationBackorderService(lifecycle, processor, documents, backorders, 2)
    private val today = LocalDate.of(2026, 11, 9)
    private val requestId = UUID.randomUUID()

    @Test
    fun `rejects a blank sku before reading backorders or runs`() {
        val command = command(skuScope = setOf("SKU", " "))

        shouldThrow<IllegalArgumentException> { service.allocate(command) }
            .message shouldBe "SKU scope must contain only non-blank SKUs."

        verify { lifecycle wasNot Called }
        verify { processor wasNot Called }
        verify { documents wasNot Called }
        verify { backorders wasNot Called }
    }

    @Test
    fun `returns a completed run without resuming it`() {
        val completed = result()
        every { lifecycle.getRun(any(), any()) } returns completed

        service.allocate(command()).block() shouldBe completed

        verify(exactly = 0) { lifecycle.beginOrResume(any(), any()) }
        verify { processor wasNot Called }
        verify { documents wasNot Called }
    }

    @Test
    fun `allocates the sorted union of the requested scope and recorded sku operations`() {
        val run = AllocationRunEntity(
            id = UUID.randomUUID(),
            requestId = requestId,
            runType = AllocationRunType.BACKORDER_FULFILLMENT,
            asOfDate = today,
            completed = false
        )
        val completed = result(run.id)
        every { lifecycle.getRun(any(), any()) } returns null
        every { lifecycle.beginOrResume(any(), any()) } returns run
        every { documents.initialize(run.id, any()) } just Runs
        every { lifecycle.getOperationSkus(run.id) } returns listOf("C", "B")
        every { processor.allocateBackorders(run.id, any(), any(), today) } returns Mono.just(emptyList())
        every { lifecycle.completeRun(run.id, setOf("A", "B", "C"), emptyList()) } returns completed

        service.allocate(command(skuScope = setOf("B", "A"))).block() shouldBe completed

        verify { processor.allocateBackorders(run.id, any(), "A", today) }
        verify { processor.allocateBackorders(run.id, any(), "B", today) }
        verify { processor.allocateBackorders(run.id, any(), "C", today) }
        verify(exactly = 0) { backorders.findDistinctSkus() }
    }

    private fun command(skuScope: Set<String>? = null) = BackorderAllocationRequest(
        requestId = requestId,
        asOfDate = today,
        trigger = BackorderAllocationTrigger.MANUAL,
        skuScope = skuScope
    )

    private fun result(runId: UUID = UUID.randomUUID()) = AllocationRunResult(
        runId = runId,
        requestId = requestId,
        runType = AllocationRunType.BACKORDER_FULFILLMENT,
        asOfDate = today,
        createdAt = Instant.parse("2026-11-09T12:00:00Z"),
        openBackorderCount = 0,
        allocations = emptyList()
    )
}

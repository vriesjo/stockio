package nl.stockio.allocation.application.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import nl.stockio.allocation.application.service.AllocationRunLifecycleService
import nl.stockio.allocation.domain.port.outbound.AllocationRecordRepository
import nl.stockio.allocation.domain.port.outbound.AllocationRunRepository
import nl.stockio.allocation.domain.port.outbound.AllocationSkuOperationRepository
import nl.stockio.allocation.domain.port.outbound.AllocationSourceRepository
import nl.stockio.allocation.domain.port.outbound.OpenBackorderRepository
import nl.stockio.allocation.infra.AllocationRecordEntity
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.allocation.infra.AllocationSourceEntity
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.AllocationSourceType
import nl.stockio.api.dto.AllocationStatus
import org.junit.jupiter.api.Test

class AllocationRunLifecycleServiceTest {
    private val runs = mockk<AllocationRunRepository>()
    private val records = mockk<AllocationRecordRepository>()
    private val sources = mockk<AllocationSourceRepository>()
    private val service = AllocationRunLifecycleService(
        runs, mockk<AllocationSkuOperationRepository>(), mockk<OpenBackorderRepository>(), records, sources
    )
    private val today = LocalDate.of(2026, 11, 9)
    private val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND)

    @Test
    fun `returns null when the request has no previous run`() {
        every { runs.findByRequestId(request.requestId) } returns null

        service.getRun(request) { _, _ -> true }.shouldBeNull()

        verify { records wasNot Called }
    }

    @Test
    fun `rejects a request id that fails the conflict check`() {
        every { runs.findByRequestId(request.requestId) } returns run(completed = false)

        shouldThrow<IllegalArgumentException> {
            service.getRun(request) { _, _ -> false }
        }.message shouldBe "requestId '${request.requestId}' has already been used with a different request."

        verify(exactly = 0) { runs.findById(any()) }
        verify { records wasNot Called }
    }

    @Test
    fun `returns null for a matching run that is still incomplete`() {
        every { runs.findByRequestId(request.requestId) } returns run(completed = false)

        service.getRun(request) { _, _ -> true }.shouldBeNull()

        verify(exactly = 0) { runs.findById(any()) }
        verify { records wasNot Called }
    }

    @Test
    fun `assembles a completed run with its records and sources`() {
        val stored = run(completed = true, openBackorderCount = 2)
        val recordId = UUID.randomUUID()
        val record = AllocationRecordEntity(
            id = recordId, runId = stored.id, sequenceNumber = 0, orderId = "order-1", orderLineId = "line-1",
            customer = "Northwind", sku = "SKU", priority = 2, placedDate = today, requiredDeliveryDate = today,
            requestedQuantity = 4, allocatedFromInventory = 3, allocatedFromInbound = 1, backorderedQuantity = 0,
            status = AllocationStatus.ALLOCATED
        )
        val source = AllocationSourceEntity(
            allocationRecordId = recordId, sourceType = AllocationSourceType.INVENTORY, sourceId = "lot-1",
            quantity = 3, sourceDate = today, warehouseLocation = "MAIN"
        )
        every { runs.findByRequestId(request.requestId) } returns stored
        every { runs.findById(stored.id) } returns Optional.of(stored)
        every { records.findAllByRunIdOrderBySequenceNumber(stored.id) } returns listOf(record)
        every { sources.findAllByAllocationRecordIdIn(listOf(recordId)) } returns listOf(source)

        val result = service.getRun(request) { previous, submitted ->
            previous.id == stored.id && submitted == request
        }

        result?.runId shouldBe stored.id
        result?.requestId shouldBe stored.requestId
        result?.openBackorderCount shouldBe 2
        result?.createdAt shouldBe stored.createdAt
        val allocation = result?.allocations?.single()
        allocation?.orderLineId shouldBe "line-1"
        allocation?.allocatedFromInventory shouldBe 3
        allocation?.allocatedFromInbound shouldBe 1
        val assembled = allocation?.sources?.single()
        assembled?.sourceType shouldBe AllocationSourceType.INVENTORY
        assembled?.sourceId shouldBe "lot-1"
        assembled?.quantity shouldBe 3
        assembled?.warehouseLocation shouldBe "MAIN"
    }

    private fun run(completed: Boolean, openBackorderCount: Int = 0) = AllocationRunEntity(
        id = UUID.randomUUID(),
        requestId = request.requestId,
        requestHash = "hash",
        runType = request.runType,
        asOfDate = today,
        completed = completed,
        openBackorderCount = openBackorderCount,
        createdAt = Instant.parse("2026-11-09T12:00:00Z")
    )
}

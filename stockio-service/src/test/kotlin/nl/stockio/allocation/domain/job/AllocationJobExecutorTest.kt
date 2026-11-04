package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.domain.service.*
import nl.stockio.api.dto.*
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import nl.stockio.stock.infra.AllocationJobResultEntity
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AllocationJobExecutorTest {
    private val properties = AllocationAdmissionProperties(true, 1, 1, 1, 1, 1, 1, 4096)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val registry = SimpleMeterRegistry()
    private val admission = AllocationAdmission(properties, registry)
    private val limits = AllocationRequestLimits(properties, mapper, registry)
    private val service = mockk<AllocationService>()
    private val projection = mockk<StockProjectionProcessor>(relaxed = true)
    private val repository = mockk<AllocationJobResultRepository>()
    private val scheduler = mockk<JobScheduler>()
    private val processor = AllocationProcessor(service, projection, mockk(), admission, limits)
    private val executor = AllocationJobExecutor(processor, repository, scheduler, mapper, limits)
    private val request = AllocationRequest(UUID.randomUUID(), LocalDate.now(), AllocationRunType.NEW_DEMAND)
    private val run = AllocationRunResult(UUID.randomUUID(), request.requestId, request.runType,
        request.asOfDate, Instant.now(), 0, emptyList())

    @Test
    fun `completion persistence remains inside the permit and failed persistence releases it`() {
        every { repository.findById(any()) } returns Optional.empty()
        every { service.allocation(request) } returns run
        every { repository.save(any()) } answers {
            assertEquals(1.0, registry.get("stockio.allocation.admission.active").gauge().value())
            error("completion storage unavailable")
        }
        assertThrows(IllegalStateException::class.java) { executor.executeSync(request.requestId, request) }
        assertEquals(0.0, registry.get("stockio.allocation.admission.active").gauge().value())
        every { repository.save(any()) } answers { firstArg() }
        assertEquals(run, executor.executeSync(request.requestId, request).allocationRun)
    }

    @Test
    fun `cached completion is available under saturation without allocation or rewriting results`() {
        val result = AllocationCompletionResult(request.requestId, run)
        every { repository.findById(request.requestId) } returns Optional.of(
            AllocationJobResultEntity(request.requestId, mapper.writeValueAsString(result)))
        admission.execute(request.runType) {
            assertEquals(result, executor.executeSync(request.requestId, request))
        }
        verify(exactly = 0) { service.allocation(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `oversized async requests are rejected before creating a durable job`() {
        val order = DemandOrder("O", "C", 1, request.asOfDate,
            listOf(DemandOrderLine("L", "A", 1, request.asOfDate)))
        assertThrows(IllegalArgumentException::class.java) {
            executor.executeAsync(request.copy(demandOrders = listOf(order, order.copy(orderId = "O2"))))
        }
        verify { scheduler wasNot Called }
        verify { repository wasNot Called }
    }
}

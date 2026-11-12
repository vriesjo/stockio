package nl.stockio.allocation.domain.processor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.performance.AllocationAdmission
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.domain.service.AllocationBackorderService
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException

class AllocationProcessorTest {
    private val registry = SimpleMeterRegistry()
    private val properties = AllocationAdmissionProperties()
    private val allocation = mockk<AllocationService>()
    private val projection = mockk<StockProjectionProcessor>()
    private val processor = AllocationProcessor(
        allocation, projection, mockk<AllocationBackorderService>(),
        AllocationAdmission(properties, registry),
        AllocationRequestLimits(properties, jacksonObjectMapper().findAndRegisterModules(), registry)
    )
    private val today = LocalDate.of(2026, 11, 9)
    private val request = AllocationRequest(UUID.randomUUID(), today, AllocationRunType.NEW_DEMAND)
    private val run = AllocationRunResult(
        UUID.randomUUID(), request.requestId, request.runType, today,
        Instant.parse("2026-11-09T12:00:00Z"), 0, emptyList()
    )

    @Test
    fun `wraps a retryable lock failure and does not project`() {
        val failure = CannotAcquireLockException("lock wait timeout")
        coEvery { allocation.allocation(request) } throws failure

        val wrapped = shouldThrow<AllocationLockContentionException> {
            runBlocking { processor.process(request) }
        }

        wrapped.cause shouldBe failure
        verify { projection wasNot Called }
    }

    @Test
    fun `propagates a failure that is not lock contention`() {
        coEvery { allocation.allocation(request) } throws IllegalStateException("bad input")

        shouldThrow<IllegalStateException> {
            runBlocking { processor.process(request) }
        }.message shouldBe "bad input"

        verify { projection wasNot Called }
    }

    @Test
    fun `turns a projection argument failure into a retryable projection exception`() {
        val failure = IllegalArgumentException("bad forecast")
        coEvery { allocation.allocation(request) } returns run
        every { projection.process(today, any()) } throws failure

        val wrapped = shouldThrow<AllocationProjectionException> {
            runBlocking { processor.process(request) }
        }

        wrapped.message shouldBe "Allocation run '${run.runId}' completed, but projection refresh failed; retry processing."
        wrapped.cause shouldBe failure
    }

    @Test
    fun `wraps lock contention raised while projecting`() {
        val failure = SQLException("lock", "55P03")
        coEvery { allocation.allocation(request) } returns run
        every { projection.process(today, any()) } throws failure

        shouldThrow<AllocationLockContentionException> {
            runBlocking { processor.process(request) }
        }.cause shouldBe failure
    }
}

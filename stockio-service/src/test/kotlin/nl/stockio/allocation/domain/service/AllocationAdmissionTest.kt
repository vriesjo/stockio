package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import nl.stockio.allocation.domain.performance.AllocationAdmission
import nl.stockio.allocation.domain.performance.AllocationAdmissionConfiguration
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.CannotAcquireLockException

class AllocationAdmissionTest {
    private val request = AllocationRequest(UUID.randomUUID(), LocalDate.now(), AllocationRunType.NEW_DEMAND)
    private val properties = AllocationAdmissionProperties(true, 1, 2, 2, 3, 3, 2, 4096)

    @Test
    fun `enabled configuration requires explicit limits while default configuration remains compatible`() {
        val runner = ApplicationContextRunner().withUserConfiguration(AllocationAdmissionConfiguration::class.java)
        runner.run { assertNull(it.startupFailure) }
        runner.withPropertyValues("stockio.allocation.admission.enabled=true").run {
            assertNotNull(it.startupFailure)
        }
        runner.withPropertyValues("stockio.allocation.admission.enabled=true",
            *listOf("max-in-flight", "max-orders", "max-shipments", "max-demand-lines",
                "max-inbound-lines", "max-distinct-skus", "max-request-bytes").map {
                "stockio.allocation.admission.$it=1"
            }.toTypedArray()).run { assertNull(it.startupFailure) }
        assertThrows(IllegalArgumentException::class.java) { properties.copy(maxRequestBytes = 0) }
    }

    @Test
    fun `projection and completion retain the shared permit before any competing allocation writes`() {
        for (blockedPhase in listOf("projection", "completion")) {
            val registry = SimpleMeterRegistry()
            val admission = AllocationAdmission(properties, registry)
            val service = mockk<AllocationService>()
            val backorders = mockk<AllocationBackorderService>()
            val projection = mockk<StockProjectionProcessor>()
            val processor = AllocationProcessor(service, projection, backorders, admission,
                AllocationRequestLimits(properties, jacksonObjectMapper().findAndRegisterModules(), registry))
            val run = AllocationRunResult(UUID.randomUUID(), request.requestId, request.runType,
                request.asOfDate, Instant.now(), 0, emptyList())
            every { service.allocation(request) } returns run
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fun block() {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            every { projection.process(any<LocalDate>(), any<Collection<String>>()) } answers {
                if (blockedPhase == "projection") block()
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val work = executor.submit<AllocationRunResult> {
                    processor.process(request) {
                        if (blockedPhase == "completion") block()
                        it
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val command = BackorderAllocationRequest(UUID.randomUUID(), request.asOfDate,
                    BackorderAllocationTrigger.STOCK_LEDGER_RECEIPT, setOf("SKU"))
                assertThrows(AllocationCapacityExceededException::class.java) { processor.process(command) }
                verify(exactly = 1) { service.allocation(any()) }
                verify(exactly = 0) { backorders.allocate(any()) }
                assertEquals(1.0, registry.get("stockio.allocation.admission.active").gauge().value())
                release.countDown()
                assertEquals(run, work.get(5, TimeUnit.SECONDS))
                assertEquals(0.0, registry.get("stockio.allocation.admission.active").gauge().value())
                assertEquals(run, processor.process(request))
                assertEquals(1.0, registry.get("stockio.allocation.admission.attempts")
                    .tag("outcome", "saturated").counter().count())
            } finally {
                release.countDown()
                executor.shutdownNow()
                registry.close()
            }
        }
    }

    @Test
    fun `failure releases the permit and is not counted as completed success`() {
        val registry = SimpleMeterRegistry()
        val admission = AllocationAdmission(properties, registry)
        assertThrows(IllegalStateException::class.java) {
            admission.execute(request.runType) { error("Projection failed after allocation committed") }
        }
        assertEquals(42, admission.execute(request.runType) { 42 })
        assertEquals(0.0, registry.get("stockio.allocation.admission.active").gauge().value())
        assertEquals(1L, registry.get("stockio.allocation.execution.duration").tag("outcome", "failure").timer().count())
        assertEquals(1L, registry.get("stockio.allocation.execution.duration").tag("outcome", "success").timer().count())
    }

    @Test
    fun `cancellation signal does not release capacity until underlying work stops`() {
        val registry = SimpleMeterRegistry()
        val admission = AllocationAdmission(properties, registry)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                try {
                    admission.execute(request.runType) {
                        entered.countDown()
                        while (release.count > 0) {
                            try {
                                release.await()
                            } catch (_: InterruptedException) {
                                // Model blocking downstream work that has not stopped on cancellation.
                            }

                            @Test
                            fun `database lock contention is surfaced as retryable allocation lock contention`() {
                                val registry = SimpleMeterRegistry()
                                val admission = AllocationAdmission(properties, registry)
                                val service = mockk<AllocationService>()
                                val backorders = mockk<AllocationBackorderService>()
                                val projection = mockk<StockProjectionProcessor>()
                                val processor = AllocationProcessor(service, projection, backorders, admission,
                                    AllocationRequestLimits(properties, jacksonObjectMapper().findAndRegisterModules(), registry))
                                every { service.allocation(request) } throws CannotAcquireLockException("lock wait timeout")

                                assertThrows(_root_ide_package_.nl.stockio.allocation.domain.performance.AllocationLockContentionException::class.java) {
                                    processor.process(request)
                                }
                            }
                        }
                    }
                } finally {
                    finished.countDown()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            future.cancel(true)
            assertThrows(AllocationCapacityExceededException::class.java) {
                admission.execute(request.runType) { fail("Must not enter") }
            }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, admission.execute(request.runType) { 1 })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

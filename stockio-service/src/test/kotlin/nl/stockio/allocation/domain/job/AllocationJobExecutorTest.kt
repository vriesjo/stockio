package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import nl.stockio.allocation.domain.performance.AllocationAdmission
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.port.AllocationGateway
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.domain.service.*
import nl.stockio.api.dto.*
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import nl.stockio.stock.infra.AllocationJobResultEntity
import org.springframework.kafka.core.KafkaTemplate
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
    private val kafkaTemplate = mockk<KafkaTemplate<String, AllocationRequest>>()
    private val jobTracking = mockk<AllocationJobTrackingService>(relaxed = true)
    private val processor = AllocationProcessor(service, projection, mockk(), admission, limits)
    private val executor =
        AllocationJobExecutor(processor, repository, mapper, limits, kafkaTemplate, jobTracking,
            "stockio.allocation.requests")
    private val request = AllocationRequest(UUID.randomUUID(), LocalDate.now(), AllocationRunType.NEW_DEMAND)
    private val run = AllocationRunResult(UUID.randomUUID(), request.requestId, request.runType,
        request.asOfDate, Instant.now(), 0, emptyList())

    @Test
    fun `completion persistence remains inside the permit and failed persistence releases it`() {
        every { repository.findById(any()) } returns Optional.empty()
        coEvery { service.allocation(request) } returns run
        every { repository.save(any()) } answers {
            assertEquals(1.0, registry.get("stockio.allocation.admission.active").gauge().value())
            error("completion storage unavailable")
        }
        assertThrows(IllegalStateException::class.java) { executor.executeSync(request.requestId, request) }
        assertEquals(0.0, registry.get("stockio.allocation.admission.active").gauge().value())
        verify { jobTracking.markProcessing(request.requestId) }
        every { repository.save(any()) } answers { firstArg() }
        assertEquals(run, executor.executeSync(request.requestId, request).allocationRun)
        verify { jobTracking.markSucceeded(request.requestId) }
    }

    @Test
    fun `cached completion is available under saturation without allocation or rewriting results`() {
        val result = AllocationCompletionResult(request.requestId, run)
        every { repository.findById(request.requestId) } returns Optional.of(
            AllocationJobResultEntity(request.requestId, mapper.writeValueAsString(result)))
        admission.execute(request.runType) {
            assertEquals(result, executor.executeSync(request.requestId, request))
        }
        coVerify(exactly = 0) { service.allocation(any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `successful execution settles the redis hold and a processing failure rolls it back`() {
        val gateway = RecordingAllocationGateway()
        val subject = AllocationJobExecutor(
            processor, repository, mapper, limits, kafkaTemplate, jobTracking,
            "stockio.allocation.requests", gateway
        )
        every { repository.findById(any()) } returns Optional.empty()
        every { repository.save(any()) } answers { firstArg() }
        coEvery { service.allocation(request) } returns run

        assertEquals(run, subject.executeSync(request.requestId, request).allocationRun)
        assertEquals(listOf("settle"), gateway.calls)

        coEvery { service.allocation(request) } throws IllegalStateException("allocation failed")
        assertThrows(IllegalStateException::class.java) { subject.executeSync(request.requestId, request) }
        assertEquals(listOf("settle", "rollback"), gateway.calls)
    }

    @Test
    fun `capacity rejection leaves the redis hold in place`() {
        val gateway = RecordingAllocationGateway()
        val subject = AllocationJobExecutor(
            processor, repository, mapper, limits, kafkaTemplate, jobTracking,
            "stockio.allocation.requests", gateway
        )
        every { repository.findById(any()) } returns Optional.empty()
        admission.execute(request.runType) {
            assertThrows(AllocationCapacityExceededException::class.java) {
                subject.executeSync(request.requestId, request)
            }
        }
        assertEquals(emptyList<String>(), gateway.calls)
    }

    @Test
    fun `async execution publishes the request keyed by its accepted job ID`() {
        val key = slot<String>()
        val sent = slot<AllocationRequest>()
        every {
            kafkaTemplate.send("stockio.allocation.requests", capture(key), capture(sent))
        } returns CompletableFuture.completedFuture(mockk(relaxed = true))

        val accepted = executor.executeAsync(request)

        assertEquals(request.requestId, accepted.requestId)
        assertEquals(request, sent.captured)
        assertEquals(accepted.jobId, UUID.fromString(key.captured))
        verify(exactly = 0) { jobTracking.beginProcessing(any(), any()) }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `async execution propagates Kafka publish failures`() {
        val publish = CompletableFuture<org.springframework.kafka.support.SendResult<String, AllocationRequest>>()
        publish.completeExceptionally(IllegalStateException("Kafka unavailable"))
        every { kafkaTemplate.send(any<String>(), any<String>(), any<AllocationRequest>()) } returns publish

        assertThrows(IllegalStateException::class.java) { executor.executeAsync(request) }
    }

    @Test
    fun `oversized async requests are rejected before creating a tracking record or publishing`() {
        val order = DemandOrder("O", "C", 1, request.asOfDate,
            listOf(DemandOrderLine("L", "A", 1, request.asOfDate)))
        assertThrows(IllegalArgumentException::class.java) {
            executor.executeAsync(request.copy(demandOrders = listOf(order, order.copy(orderId = "O2"))))
        }
        verify(exactly = 0) { kafkaTemplate.send(any<String>(), any<String>(), any<AllocationRequest>()) }
        verify(exactly = 0) { jobTracking.beginProcessing(any(), any()) }
        verify { repository wasNot Called }
    }

    @Test
    fun `failed Kafka acknowledgement does not write job tracking`() {
        val publish = CompletableFuture<org.springframework.kafka.support.SendResult<String, AllocationRequest>>()
        publish.completeExceptionally(IllegalStateException("Kafka acknowledgement timed out"))
        every { kafkaTemplate.send(any<String>(), any<String>(), any<AllocationRequest>()) } returns publish

        assertThrows(IllegalStateException::class.java) { executor.executeAsync(request) }

        verify(exactly = 0) { jobTracking.beginProcessing(any(), any()) }
    }
}

private class RecordingAllocationGateway : AllocationGateway {
    override val enabled = true
    val calls = mutableListOf<String>()

    override fun accept(request: AllocationRequest) {
        calls += "accept"
    }

    override fun rollback(request: AllocationRequest) {
        calls += "rollback"
    }

    override fun settle(request: AllocationRequest) {
        calls += "settle"
    }

    override fun getByKey(key: String): RedisKeyResponse? = null
}

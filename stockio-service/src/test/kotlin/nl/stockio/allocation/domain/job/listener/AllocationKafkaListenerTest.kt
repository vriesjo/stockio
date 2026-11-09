package nl.stockio.allocation.domain.job.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class AllocationKafkaListenerTest {

    @Test
    fun `rejects JSON null as an invalid allocation record`() {
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        val listener = AllocationKafkaListener(
            ObjectMapper(),
            Mockito.mock(AllocationRequestLimits::class.java),
            workQueue,
            Mockito.mock(nl.stockio.allocation.domain.job.AllocationJobTrackingService::class.java)
        )

        assertThrows(IllegalArgumentException::class.java) { listener.receive("null") }
        assertThrows(IllegalArgumentException::class.java) { listener.receive("{") }
        Mockito.verifyNoInteractions(workQueue)
    }

    @Test
    fun `waits for worker completion before returning to Kafka`() {
        val request = request()
        val submitted = CountDownLatch(1)
        val completion = CompletableFuture<Void>()
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        Mockito.`when`(workQueue.submit(request)).thenAnswer {
            submitted.countDown()
            completion
        }
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val listener = AllocationKafkaListener(
            mapper,
            Mockito.mock(AllocationRequestLimits::class.java),
            workQueue,
            Mockito.mock(nl.stockio.allocation.domain.job.AllocationJobTrackingService::class.java)
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val listenerCall = executor.submit {
                listener.receive(mapper.writeValueAsString(request))
            }
            if (!submitted.await(2, TimeUnit.SECONDS)) listenerCall.get(2, TimeUnit.SECONDS)
            assertTrue(submitted.count == 0L)
            assertFalse(listenerCall.isDone)

            completion.complete(null)
            listenerCall.get(2, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `uses a UUID record key as the allocation execution ID`() {
        val request = request()
        val jobId = UUID.randomUUID()
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val workQueue = Mockito.mock(AllocationKafkaWorkQueue::class.java)
        Mockito.`when`(workQueue.submit(request, jobId))
            .thenReturn(CompletableFuture.completedFuture(null))
        val listener = AllocationKafkaListener(
            mapper,
            Mockito.mock(AllocationRequestLimits::class.java),
            workQueue,
            Mockito.mock(nl.stockio.allocation.domain.job.AllocationJobTrackingService::class.java)
        )

        listener.receive(mapper.writeValueAsString(request), jobId.toString())

        Mockito.verify(workQueue).submit(request, jobId)
    }

    @Test
    fun `backs off capacity rejection and completes after allocation succeeds`() {
        val request = request()
        val jobId = UUID.randomUUID()
        val executor = Mockito.mock(AllocationJobExecutor::class.java)
        val attempts = AtomicInteger()
        Mockito.doAnswer {
            if (attempts.incrementAndGet() == 1) throw AllocationCapacityExceededException()
            Mockito.mock(AllocationCompletionResult::class.java)
        }.`when`(executor).executeSync(jobId, request)
        val queue = workQueue(executor, properties(capacityBackoffMin = Duration.ofMillis(1)))

        try {
            queue.submit(request, jobId).get(2, TimeUnit.SECONDS)
            Mockito.verify(executor, Mockito.times(2)).executeSync(jobId, request)
        } finally {
            queue.stop()
        }
    }

    @Test
    fun `backs off lock contention and completes after allocation succeeds`() {
        val request = request()
        val executor = Mockito.mock(AllocationJobExecutor::class.java)
        val attempts = AtomicInteger()
        Mockito.doAnswer {
            if (attempts.incrementAndGet() == 1) throw AllocationLockContentionException()
            Mockito.mock(AllocationCompletionResult::class.java)
        }.`when`(executor).executeSync(request.requestId, request)
        val queue = workQueue(executor, properties(capacityBackoffMin = Duration.ofMillis(1)))

        try {
            queue.submit(request).get(2, TimeUnit.SECONDS)
            Mockito.verify(executor, Mockito.times(2)).executeSync(request.requestId, request)
        } finally {
            queue.stop()
        }
    }

    @Test
    fun `waits for bounded channel capacity instead of dropping a record`() {
        val executor = Mockito.mock(AllocationJobExecutor::class.java)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val requests = List(3) { request() }
        Mockito.doAnswer {
            firstStarted.countDown()
            check(releaseFirst.await(2, TimeUnit.SECONDS))
            Mockito.mock(AllocationCompletionResult::class.java)
        }.`when`(executor).executeSync(requests.first().requestId, requests.first())
        val queue = workQueue(executor, properties(workerCount = 1, channelCapacity = 1))

        try {
            val completions = requests.map(queue::submit)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertFalse(completions.last().isDone)

            releaseFirst.countDown()
            completions.forEach { it.get(2, TimeUnit.SECONDS) }
            requests.forEach { Mockito.verify(executor).executeSync(it.requestId, it) }
        } finally {
            releaseFirst.countDown()
            queue.stop()
        }
    }

    @Test
    fun `completes after durable JobRunr fallback is enqueued`() {
        val request = request()
        val executor = Mockito.mock(AllocationJobExecutor::class.java)
        val storageProvider = Mockito.mock(StorageProvider::class.java)
        val scheduler = Mockito.mock(JobScheduler::class.java)
        Mockito.doThrow(IllegalStateException("Synchronous execution failed"))
            .`when`(executor).executeSync(request.requestId, request)
        Mockito.doThrow(JobNotFoundException(request.requestId))
            .`when`(storageProvider).getJobById(request.requestId)
        val queue = AllocationKafkaWorkQueue(
            properties(),
            executor,
            scheduler,
            storageProvider,
            SimpleMeterRegistry()
        )

        try {
            queue.submit(request).get(2, TimeUnit.SECONDS)
            val lambda = ArgumentCaptor.forClass(JobLambda::class.java)
            Mockito.verify(scheduler).enqueue(Mockito.eq(request.requestId), lambda.capture())
        } finally {
            queue.stop()
        }
    }

    private fun workQueue(
        executor: AllocationJobExecutor,
        properties: AllocationKafkaProperties = properties()
    ) = AllocationKafkaWorkQueue(
        properties,
        executor,
        Mockito.mock(JobScheduler::class.java),
        Mockito.mock(StorageProvider::class.java),
        SimpleMeterRegistry()
    )

    private fun properties(
        workerCount: Int = 1,
        channelCapacity: Int = 1,
        capacityBackoffMin: Duration = Duration.ofMillis(1)
    ) = AllocationKafkaProperties(
        workerCount = workerCount,
        channelCapacity = channelCapacity,
        capacityBackoffMin = capacityBackoffMin,
        capacityBackoffMax = capacityBackoffMin
    )

    private fun request() = AllocationRequest(
        UUID.randomUUID(),
        LocalDate.now(),
        AllocationRunType.NEW_DEMAND
    )
}

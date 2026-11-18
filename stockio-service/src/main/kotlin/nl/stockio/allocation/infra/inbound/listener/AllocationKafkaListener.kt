package nl.stockio.allocation.infra.inbound.listener

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.job.AllocationJobTrackingService
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.application.service.AllocationRequestLimits
import nl.stockio.allocation.application.service.AllocationRequestTooLargeException
import nl.stockio.api.dto.AllocationRequest
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import nl.stockio.allocation.domain.job.listener.AllocationKafkaProperties

private val logger = KotlinLogging.logger {}

@Component
class AllocationKafkaListener(
    private val limits: AllocationRequestLimits,
    private val workQueue: AllocationKafkaWorkQueue,
    private val jobTracking: AllocationJobTrackingService,
) {
    @KafkaListener(
        id = "stockioAllocationKafkaListener",
        topics = ["\${stockio.allocation.kafka.topic:stockio.allocation.requests}"],
        groupId = "\${stockio.allocation.kafka.group-id:stockio-allocation}",
        concurrency = "\${stockio.allocation.kafka.listener-concurrency:2}",
        autoStartup = "\${stockio.allocation.kafka.enabled:true}",
        containerFactory = "allocationKafkaListenerContainerFactory",
    )
    fun receive(record: ConsumerRecord<String, AllocationRequest?>) {
        val request =
            record.value()
                ?: throw IllegalArgumentException("Kafka allocation record has no value.")
        val framedBytes = record.serializedValueSize()
        if (framedBytes >= 0) limits.checkBytes(framedBytes.toLong())
        val jobId =
            record.key()?.let {
                try {
                    UUID.fromString(it)
                } catch (exception: IllegalArgumentException) {
                    throw IllegalArgumentException("Kafka allocation record key must be a UUID.", exception)
                }
            } ?: request.requestId
        jobTracking.beginProcessing(jobId, request.requestId)
        try {
            workQueue.submit(request, jobId).get()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
    }
}

@Component
class AllocationKafkaWorkQueue(
    private val properties: AllocationKafkaProperties,
    private val allocationJobExecutor: AllocationJobExecutor,
    private val jobScheduler: JobScheduler,
    private val storageProvider: StorageProvider,
    private val registry: MeterRegistry,
) {
    private data class Work(
        val request: AllocationRequest,
        val jobId: UUID,
        val completion: CompletableFuture<Void>,
    )

    // Bounded channel to limit the number of in-flight allocation requests being processed concurrently.
    // Used to handle burst traffic and prevent overwhelming the system with too many concurrent requests.
    private val channel = Channel<Work>(properties.channelCapacity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accepting = AtomicBoolean(true)
    private val inFlight = AtomicInteger()

    init {
        // Register a gauge metric to track the number of in-flight allocation requests being processed concurrently.
        registry.gauge("stockio.allocation.kafka.inflight", inFlight)
        // Launch a fixed number of worker coroutines to process allocation requests from the channel.
        // Each worker coroutine will process requests sequentially from the channel.
        // Each stays in for (work in channel), waiting for and processing work as it arrives.
        repeat(properties.workerCount) {
            scope.launch {
                for (work in channel) {
                    process(work)
                }
            }
        }
    }

    fun submit(
        request: AllocationRequest,
        jobId: UUID = request.requestId,
    ): CompletableFuture<Void> {
        // Create a CompletableFuture to track the completion of the work.
        // The inFlight counter is incremented to track the number of concurrent requests being processed.
        val completion = CompletableFuture<Void>()
        inFlight.incrementAndGet()
        // Reset the inFlight counter when the work is completed, regardless of success or failure.
        completion.whenComplete { _, _ -> inFlight.decrementAndGet() }
        if (!accepting.get()) {
            completion.completeExceptionally(IllegalStateException("Kafka allocation workers are stopping."))
            return completion
        }
        // Launch a coroutine immediately to send the work to the channel.
        // If the channel is full, this will suspend until space is available.
        val enqueue =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    channel.send(Work(request, jobId, completion))
                } catch (exception: CancellationException) {
                    completion.completeExceptionally(exception)
                } catch (exception: Exception) {
                    completion.completeExceptionally(exception)
                }
            }
        if (enqueue.isCancelled) {
            completion.completeExceptionally(IllegalStateException("Kafka allocation workers are stopping."))
        }
        return completion
    }

    private suspend fun process(work: Work) {
        val startedAt = System.nanoTime()
        try {
            executeWithFailurePolicy(work.jobId, work.request)
            work.completion.complete(null)
            record("completed")
        } catch (exception: CancellationException) {
            work.completion.completeExceptionally(exception)
            throw exception
        } catch (exception: Exception) {
            work.completion.completeExceptionally(exception)
            record("failed")
        } finally {
            registry
                .timer("stockio.allocation.kafka.processing.duration")
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS)
        }
    }

    // Executes the allocation request with a failure policy that handles capacity exceeded exceptions and other errors.
    // If the allocation capacity is exceeded, it will pause for a backoff period before retrying.
    // If other exceptions occur, it will enqueue a JobRunr fallback for asynchronous processing.
    private suspend fun executeWithFailurePolicy(
        jobId: UUID,
        request: AllocationRequest,
    ) {
        while (true) {
            try {
                allocationJobExecutor.executeSync(jobId, request)
                logger.info { "Processed Kafka allocation request '${request.requestId}' synchronously." }
                return
            } catch (_: AllocationCapacityExceededException) {
                record("capacity_deferred")
                if (!accepting.get()) throw CancellationException("Kafka allocation worker is stopping.")
                delay(nextCapacityBackoffMillis())
            } catch (_: AllocationLockContentionException) {
                record("lock_contention_deferred")
                if (!accepting.get()) throw CancellationException("Kafka allocation worker is stopping.")
                delay(nextCapacityBackoffMillis())
            } catch (exception: AllocationRequestTooLargeException) {
                logger.error(exception) {
                    "Kafka allocation request '${request.requestId}' exceeds the byte limit; rejecting it to the dead-letter topic."
                }
                throw exception
            } catch (exception: IllegalArgumentException) {
                logger.error(exception) {
                    "Kafka allocation request '${request.requestId}' is invalid; rejecting it to the dead-letter topic."
                }
                throw exception
            } catch (exception: CancellationException) {
                logger.warn(exception) { "Kafka allocation worker is stopping; cancelling processing of request '${request.requestId}'." }
                throw exception
            } catch (exception: Exception) {
                logger.error(exception) {
                    "Synchronous Kafka processing of allocation request '${request.requestId}' failed; " +
                        "enqueueing JobRunr fallback."
                }
                enqueueJobRunrFallback(jobId, request, exception)
                record("jobrunr_fallback")
                return
            }
        }
    }

    private fun enqueueJobRunrFallback(
        jobId: UUID,
        request: AllocationRequest,
        exception: Exception,
    ) {
        logger.error(exception) {
            "Synchronous Kafka processing of allocation request '${request.requestId}' failed; " +
                "enqueueing JobRunr fallback."
        }
        val existingJob =
            try {
                storageProvider.getJobById(jobId)
            } catch (_: JobNotFoundException) {
                null
            }
        if (existingJob != null) {
            logger.info { "JobRunr job '$jobId' already exists; skipping enqueue." }
            return
        }
        jobScheduler.enqueue(jobId) {
            logger.info { "Async executing Kafka allocation job $jobId for request ${request.requestId}." }
            allocationJobExecutor.executeSync(jobId, request)
        }
    }

    // Calculates the next backoff duration in milliseconds for handling allocation capacity exceeded exceptions.
    // The backoff duration is randomly selected between the configured minimum and maximum values
    // to avoid the thundering herd problem when multiple requests are retried simultaneously.
    private fun nextCapacityBackoffMillis(): Long {
        val minimum = properties.capacityBackoffMin.toMillis()
        val maximum = properties.capacityBackoffMax.toMillis()
        return if (minimum == maximum) minimum else ThreadLocalRandom.current().nextLong(minimum, maximum + 1)
    }

    private fun record(outcome: String) {
        registry.counter("stockio.allocation.kafka.records", "outcome", outcome).increment()
    }

    // Stop accepting new work and cancel all coroutines in the scope.
    // This method is called when the application is shutting down to ensure
    // that all in-flight work is completed or cancelled gracefully.
    @PreDestroy
    fun stop() {
        if (accepting.compareAndSet(true, false)) {
            channel.close()
            scope.cancel()
            while (true) {
                val work = channel.tryReceive().getOrNull() ?: break
                work.completion.completeExceptionally(IllegalStateException("Kafka allocation workers stopped."))
            }
        }
    }
}

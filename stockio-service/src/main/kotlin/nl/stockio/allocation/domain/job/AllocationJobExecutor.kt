package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ExecutionException
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.infra.DisabledAllocationRedisGateway
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.domain.port.AllocationGateway
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import nl.stockio.stock.infra.AllocationJobResultEntity
import org.jobrunr.jobs.annotations.Job
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

private val logger = KotlinLogging.logger {}

/**
 * Executes allocation jobs, either synchronously or asynchronously.
 *
 * This service is responsible for executing allocation jobs, either synchronously or asynchronously.
 * It uses the [AllocationProcessor] to process the allocation request and the [AllocationJobCompletionService]
 * to complete the job and store the result in the [AllocationJobResultRepository].
 *
 * The asynchronous execution publishes the request to Kafka for later processing.
 */
@Service
class AllocationJobExecutor(
    private val allocationProcessor: AllocationProcessor,
    private val jobResultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper,
    private val limits: AllocationRequestLimits,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val jobTracking: AllocationJobTrackingService,
    @param:Value("\${stockio.allocation.kafka.topic:stockio.allocation.requests}") private val topic: String,
    private val allocationRedisGateway: AllocationGateway = DisabledAllocationRedisGateway()
) {

    /**
     * Publishes [request] under a new job ID and returns its submission identifiers.
     *
     * Each submission gets a new job ID; [AllocationRequest.requestId] remains the logical
     * request identity used for allocation idempotency. Repeated submissions may therefore
     * publish separate records for the same logical request.
     *
     * Returns only after Kafka acknowledges the publish; publishing failures propagate to the caller.
     * This method does not validate the request's business date or execute allocation itself.
     *
     * @param request allocation request forwarded unchanged to [AllocationJobExecutor.executeSync].
     * @return the job ID and request ID. The job is not tracked until the Kafka listener takes the record.
     */
    fun executeAsync(request: AllocationRequest): AllocationJobAccepted {
        limits.validate(request)
        val jobId = UUID.randomUUID()
        val payload = objectMapper.writeValueAsString(request)
        logger.info {
            "Publishing allocation job $jobId for request ${request.requestId} to Kafka topic '$topic'."
        }
        try {
            kafkaTemplate.send(topic, jobId.toString(), payload).get()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
        return AllocationJobAccepted(jobId, request.requestId)
    }

    /**
     * Executes [request] under [jobId] and returns the allocation result.
     *
     * This method is intended for synchronous execution of allocation jobs, typically invoked
     * by a message consumer or for testing purposes. It checks if a result already
     * exists for the given job ID and returns it if found; otherwise, it processes the request
     * and completes the job.
     *
     * @param jobId unique identifier for this execution instance.
     * @param request allocation request to be processed.
     * @return the allocation completion result, either retrieved from storage or newly computed.
     */
    /**
     * Reactive execution used by inline HTTP. Cache lookup, Redis, and completion stay on the bounded elastic
     * scheduler. The allocation publisher itself does not hold that thread.
     */
    fun execute(jobId: UUID, request: AllocationRequest): Mono<AllocationCompletionResult> =
        Mono.fromCallable {
            jobTracking.markProcessing(jobId)
            Optional.ofNullable(jobResultRepository.findById(jobId).orElse(null)?.let { cached ->
                jobTracking.markSucceeded(jobId)
                objectMapper.readValue(cached.resultJson, AllocationCompletionResult::class.java)
            })
        }.subscribeOn(Schedulers.boundedElastic()).flatMap { cached ->
            if (cached.isPresent) Mono.just(cached.get())
            else allocationProcessor.process(request) { allocationRun -> complete(jobId, allocationRun) }
        }.flatMap { result ->
            Mono.fromCallable {
                allocationRedisGateway.settle(request)
                result
            }.subscribeOn(Schedulers.boundedElastic())
        }.onErrorResume { exception ->
            if (exception is AllocationCapacityExceededException || exception is AllocationLockContentionException) {
                Mono.error(exception)
            } else {
                Mono.fromCallable { allocationRedisGateway.rollback(request) }
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.error(exception))
            }
        }

    /**
     * One blocking wait for Kafka, RabbitMQ, and JobRunr. Those callers already run off the Netty event loop.
     */
    @Job(name = "Execute Allocation Request", retries = 3, jobFilters = [AllocationCapacityRetryFilter::class])
    fun executeSync(jobId: UUID, request: AllocationRequest): AllocationCompletionResult =
        execute(jobId, request).block()!!

    fun complete(
        jobId: UUID,
        allocationRun: AllocationRunResult
    ): AllocationCompletionResult {
        jobResultRepository.findById(jobId).orElse(null)?.let {
            return objectMapper.readValue(it.resultJson, AllocationCompletionResult::class.java)
        }

        val result = AllocationCompletionResult(jobId, allocationRun)
        jobResultRepository.save(
            AllocationJobResultEntity(
                jobId = jobId,
                resultJson = objectMapper.writeValueAsString(result)
            )
        )
        jobTracking.markSucceeded(jobId)
        return result
    }
}

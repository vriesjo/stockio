package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.stockio.util.blockingElastic
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.infra.DisabledAllocationRedisGateway
import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.domain.port.outbound.AllocationGateway
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.allocation.application.service.AllocationRequestLimits
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import nl.stockio.stock.infra.AllocationJobResultEntity
import org.jobrunr.jobs.annotations.Job
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

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
    private val kafkaTemplate: KafkaTemplate<String, AllocationRequest>,
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
        logger.info {
            "Publishing allocation job $jobId for request ${request.requestId} to Kafka topic '$topic'."
        }
        try {
            kafkaTemplate.send(topic, jobId.toString(), request).get()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
        return AllocationJobAccepted(jobId, request.requestId)
    }

    /**
     * Runs [request] under [jobId] and returns the allocation result.
     *
     * Inline HTTP calls this suspending function. A stored completion is reused when one exists;
     * otherwise [AllocationProcessor.process] runs and [complete] stores the result.
     * Cache lookup, Redis settle, and Redis rollback hop to Reactor's bounded elastic dispatcher.
     * Allocation itself is a suspending call and does not hold that worker.
     *
     * [AllocationCapacityExceededException] and [AllocationLockContentionException] propagate with the
     * provisional Redis hold left in place. Any other failure rolls that hold back and is rethrown.
     */
    suspend fun execute(jobId: UUID, request: AllocationRequest): AllocationCompletionResult =
        try {
            val cached = withContext(blockingElastic) {
                jobTracking.markProcessing(jobId)
                jobResultRepository.findById(jobId).orElse(null)?.let { stored ->
                    jobTracking.markSucceeded(jobId)
                    objectMapper.readValue(stored.resultJson, AllocationCompletionResult::class.java)
                }
            }
            val result = cached ?: allocationProcessor
                .process(request) { allocationRun -> complete(jobId, allocationRun) }
            withContext(blockingElastic) {
                allocationRedisGateway.settle(request)
                result
            }
        } catch (exception: AllocationCapacityExceededException) {
            throw exception
        } catch (exception: AllocationLockContentionException) {
            throw exception
        } catch (exception: CancellationException) {
            rollback(request)
            throw exception
        } catch (exception: Exception) {
            rollback(request)
            throw exception
        }

    /**
     * One blocking wait for Kafka, RabbitMQ, and JobRunr. Those callers already run off the Netty event loop.
     */
    @Job(name = "Execute Allocation Request", retries = 3, jobFilters = [AllocationCapacityRetryFilter::class])
    fun executeSync(jobId: UUID, request: AllocationRequest): AllocationCompletionResult =
        runBlocking { execute(jobId, request) }

    private suspend fun rollback(request: AllocationRequest) {
        withContext(blockingElastic + NonCancellable) { allocationRedisGateway.rollback(request) }
    }

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

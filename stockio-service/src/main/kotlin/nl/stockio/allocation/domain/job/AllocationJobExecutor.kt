package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRequestEvent
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.domain.processor.AllocationProcessor
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import nl.stockio.stock.infra.AllocationJobResultEntity
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Executes allocation jobs, either synchronously or asynchronously.
 *
 * This service is responsible for executing allocation jobs, either synchronously or asynchronously.
 * It uses the [AllocationProcessor] to process the allocation request and the [AllocationJobCompletionService]
 * to complete the job and store the result in the [AllocationJobResultRepository].
 *
 * The asynchronous execution is handled by the [JobScheduler], which enqueues the job for later execution.
 */
@Service
class AllocationJobExecutor(
    private val allocationProcessor: AllocationProcessor,
    private val jobResultRepository: AllocationJobResultRepository,
    private val jobScheduler: JobScheduler,
    private val objectMapper: ObjectMapper
) {

    /**
     * Enqueues [request] under a new job ID and returns its submission identifiers.
     *
     * Each submission gets a new job ID; [AllocationRequest.requestId] remains the logical
     * request identity used for allocation idempotency. Repeated submissions may therefore
     * enqueue separate jobs for the same logical request.
     *
     * Returns only after enqueueing succeeds; scheduling failures propagate to the caller.
     * This method does not validate the request's business date or execute allocation itself.
     *
     * @param request allocation request forwarded unchanged to [AllocationJobExecutor.executeSync].
     * @return the job ID for status polling and request ID for correlation, not an allocation result.
     */
    fun executeAsync(request: AllocationRequest): AllocationJobAccepted {
        val event = AllocationRequestEvent(UUID.randomUUID(), request)
        logger.info { "Enqueueing allocation job ${event.jobId} for request ${event.request.requestId}" }
        jobScheduler.enqueue(event.jobId) {
            executeSync(event.jobId, event.request)
        }

        return AllocationJobAccepted(event.jobId, event.request.requestId)
    }

    /**
     * Executes [request] under [jobId] and returns the allocation result.
     *
     * This method is intended for synchronous execution of allocation jobs, typically invoked
     * by the asynchronous job executor or for testing purposes. It checks if a result already
     * exists for the given job ID and returns it if found; otherwise, it processes the request
     * and completes the job.
     *
     * @param jobId unique identifier for this execution instance.
     * @param request allocation request to be processed.
     * @return the allocation completion result, either retrieved from storage or newly computed.
     */
    @Job(name = "Execute Allocation Request", retries = 3)
    fun executeSync(jobId: UUID, request: AllocationRequest): AllocationCompletionResult {
        jobResultRepository.findById(jobId).orElse(null)?.let {
            return objectMapper.readValue(it.resultJson, AllocationCompletionResult::class.java)
        }
        val allocationRun = allocationProcessor.process(request)
        val completionResult = complete(jobId, allocationRun)
        return completionResult
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
        return result
    }
}

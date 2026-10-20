package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationCompletionResult
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.infra.AllocationJobResultRepository
import nl.stockio.allocation.domain.service.AllocationProcessingService
import org.jobrunr.jobs.annotations.Job
import org.springframework.stereotype.Service

@Service
class AllocationJob(
    private val processingService: AllocationProcessingService,
    private val completionService: AllocationJobCompletionService,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    @Job(name = "Execute Allocation Request", retries = 3)
    fun execute(jobId: UUID, request: AllocationRequest): AllocationCompletionResult {
        resultRepository.findById(jobId).orElse(null)?.let {
            return objectMapper.readValue(it.resultJson, AllocationCompletionResult::class.java)
        }
        val allocationRun = processingService.process(request)
        return completionService.complete(jobId, allocationRun)
    }
}

package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.model.AllocationCompletionResult
import nl.stockio.stock.infra.AllocationJobResultJpaRepository
import nl.stockio.allocation.domain.processor.AllocationProcessor
import org.jobrunr.jobs.annotations.Job
import org.springframework.stereotype.Service

@Service
class AllocationJob(
    private val allocationProcessor: AllocationProcessor,
    private val jobCompletionService: AllocationJobCompletionService,
    private val jobResultRepository: AllocationJobResultJpaRepository,
    private val objectMapper: ObjectMapper
) {

    @Job(name = "Execute Allocation Request", retries = 3)
    fun execute(jobId: UUID, request: AllocationRequest): AllocationCompletionResult {
        jobResultRepository.findById(jobId).orElse(null)?.let {
            return objectMapper.readValue(it.resultJson, AllocationCompletionResult::class.java)
        }
        val allocationRun = allocationProcessor.process(request)
        return jobCompletionService.complete(jobId, allocationRun)
    }
}

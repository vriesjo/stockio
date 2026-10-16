package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationAndSyncResult
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunResult
import nl.stockio.allocation.domain.bean.ProcessDailyStockRequest
import nl.stockio.allocation.infra.AllocationJobResultRepository
import nl.stockio.allocation.domain.service.AllocationService
import org.jobrunr.jobs.annotations.Job
import org.springframework.stereotype.Service

@Service
class AllocationJob(
    private val allocationService: AllocationService,
    private val completionService: AllocationJobCompletionService,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    @Job(name = "Execute Allocation Request", retries = 3)
    fun execute(jobId: UUID, request: AllocationRequest): AllocationAndSyncResult {
        resultRepository.findById(jobId).orElse(null)?.let {
            return objectMapper.readValue(it.resultJson, AllocationAndSyncResult::class.java)
        }
        val allocationRun = allocationService.allocation(request)
        return completionService.complete(
            jobId,
            allocationRun,
            ProcessDailyStockRequest(request.asOfDate)
        )
    }
}

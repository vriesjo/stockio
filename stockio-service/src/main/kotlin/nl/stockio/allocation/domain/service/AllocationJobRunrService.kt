package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.navigation.AmountRequest
import org.springframework.stereotype.Service

@Service
class AllocationJobRunrService(
    private val storageProvider: StorageProvider,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    fun getStatusById(jobId: UUID): AllocationJobStatus {
        val job = storageProvider.getJobById(jobId)
        val result = if (job.state == StateName.SUCCEEDED) {
            val saved = resultRepository.findById(jobId).orElseThrow {
                IllegalStateException("Succeeded allocation job '$jobId' has no saved completion result.")
            }
            objectMapper.readValue(saved.resultJson, AllocationCompletionResult::class.java)
        } else {
            null
        }
        return AllocationJobStatus(
            jobId = jobId,
            status = job.state.name,
            result = result
        )
    }

    fun deleteJobsWithRetriesByStatus() {
        val failedJobs = storageProvider.getJobList(StateName.PROCESSING, AmountRequest("updatedAt:ASC", 100)) +
            storageProvider.getJobList(StateName.SCHEDULED, AmountRequest("updatedAt:ASC", 100))
        for (job in failedJobs) {
            // JobRunr records a state history; multiple Failed/Processing transitions indicate retries.
            val retryCount = job.jobStates.count { it.name == StateName.SCHEDULED || it.name == StateName.PROCESSING || it.name == StateName.FAILED }

            if (retryCount > 2) {
                storageProvider.deletePermanently(job.id)
            }
        }
    }
}

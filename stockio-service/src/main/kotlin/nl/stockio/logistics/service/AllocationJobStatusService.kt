package nl.stockio.logistics.service

import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.logistics.domain.AllocationAndSyncResult
import nl.stockio.logistics.domain.AllocationJobStatus
import nl.stockio.logistics.persistence.AllocationJobResultRepository
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.StorageProvider
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AllocationJobStatusService(
    private val storageProvider: StorageProvider,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper
) {

    fun getStatus(jobId: UUID): AllocationJobStatus {
        val job = storageProvider.getJobById(jobId)
        val result = if (job.state == StateName.SUCCEEDED) {
            resultRepository.findById(jobId)
                .map { objectMapper.readValue(it.resultJson, AllocationAndSyncResult::class.java) }
                .orElse(null)
        } else {
            null
        }
        return AllocationJobStatus(
            jobId = jobId,
            status = job.state.name,
            result = result
        )
    }
}

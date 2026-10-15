package nl.stockio.allocation.domain.job

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationAndSyncResult
import nl.stockio.allocation.domain.bean.AllocationJobStatus
import nl.stockio.allocation.infra.AllocationJobResultRepository
import org.jobrunr.jobs.states.StateName
import org.jobrunr.storage.StorageProvider
import org.springframework.stereotype.Service

@Service
class AllocationJobService(
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

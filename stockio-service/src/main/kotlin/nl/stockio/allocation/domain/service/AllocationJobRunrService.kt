package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import org.jobrunr.jobs.states.StateName
import org.jobrunr.server.BackgroundJobServer
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.navigation.AmountRequest
import org.springframework.stereotype.Service

@Service
class AllocationJobRunrService(
    private val storageProvider: StorageProvider,
    private val resultRepository: AllocationJobResultRepository,
    private val objectMapper: ObjectMapper,
    private val backgroundJobServers: List<BackgroundJobServer>
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

    fun flushAllJobs() {

        backgroundJobServers.forEach { server ->
            server.stop()
        }

        Thread.sleep(2000) // Wait for the server to stop and release locks

        val statesToFlush = listOf(
            StateName.PROCESSING,
            StateName.FAILED,
            StateName.ENQUEUED,
            StateName.SCHEDULED,
            StateName.SUCCEEDED,
            StateName.AWAITING,
        )

        for (state in statesToFlush) {
            var deletedCount = 0
            do {
                // Fetch jobs in batches of 500 (storage provider max batch limits apply)
                val jobs = storageProvider.getJobList(state, AmountRequest("updatedAt:ASC", 500))
                for (job in jobs) {
                    try {
                        // Use permanent delete or JobScheduler delete depending on state
                        storageProvider.deletePermanently(job.id)
                    } catch (e: Exception) {
                        // Handle active locks if processing
                    }
                }
                deletedCount = jobs.size
            } while (deletedCount > 0)
        }
        backgroundJobServers.forEach { server ->
            server.start()
        }
    }
}

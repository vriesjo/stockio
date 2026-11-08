package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import nl.stockio.api.dto.AllocationCompletionResult
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.allocation.domain.job.AllocationJobTrackingService
import nl.stockio.allocation.domain.job.AllocationJobTrackingState
import nl.stockio.stock.domain.port.AllocationJobResultRepository
import nl.stockio.stock.infra.AllocationJobResultEntity
import nl.stockio.stock.infra.AllocationJobTrackingEntity
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class AllocationJobRunrServiceTest {

    @Test
    fun `returns persisted Kafka submission status when there is no JobRunr job`() {
        val jobId = UUID.randomUUID()
        val tracking = Mockito.mock(AllocationJobTrackingService::class.java)
        val resultRepository = Mockito.mock(AllocationJobResultRepository::class.java)
        val storageProvider = Mockito.mock(StorageProvider::class.java)
        Mockito.`when`(tracking.findById(jobId)).thenReturn(
            Optional.of(AllocationJobTrackingEntity(jobId, UUID.randomUUID(), AllocationJobTrackingState.ENQUEUED))
        )
        Mockito.`when`(storageProvider.getJobById(jobId)).thenThrow(JobNotFoundException(jobId))
        Mockito.`when`(resultRepository.findById(jobId)).thenReturn(Optional.empty())
        val service = service(storageProvider, resultRepository, tracking)

        assertEquals(AllocationJobStatus(jobId, "ENQUEUED"), service.getStatusById(jobId))
    }

    @Test
    fun `returns saved completion even if tracking transition has not yet reached succeeded`() {
        val jobId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val run = AllocationRunResult(
            UUID.randomUUID(),
            requestId,
            AllocationRunType.NEW_DEMAND,
            LocalDate.now(),
            Instant.now(),
            0,
            emptyList()
        )
        val completion = AllocationCompletionResult(jobId, run)
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val tracking = Mockito.mock(AllocationJobTrackingService::class.java)
        val resultRepository = Mockito.mock(AllocationJobResultRepository::class.java)
        val storageProvider = Mockito.mock(StorageProvider::class.java)
        Mockito.`when`(tracking.findById(jobId)).thenReturn(
            Optional.of(AllocationJobTrackingEntity(jobId, requestId, AllocationJobTrackingState.PROCESSING))
        )
        Mockito.`when`(storageProvider.getJobById(jobId)).thenThrow(JobNotFoundException(jobId))
        Mockito.`when`(resultRepository.findById(jobId)).thenReturn(
            Optional.of(AllocationJobResultEntity(jobId, mapper.writeValueAsString(completion)))
        )
        val service = AllocationJobRunrService(
            storageProvider,
            resultRepository,
            tracking,
            mapper,
            emptyList()
        )

        assertEquals(AllocationJobStatus(jobId, "SUCCEEDED", completion), service.getStatusById(jobId))
    }

    @Test
    fun `reports a consistency error for succeeded tracked jobs without a result`() {
        val jobId = UUID.randomUUID()
        val tracking = Mockito.mock(AllocationJobTrackingService::class.java)
        val resultRepository = Mockito.mock(AllocationJobResultRepository::class.java)
        val storageProvider = Mockito.mock(StorageProvider::class.java)
        Mockito.`when`(tracking.findById(jobId)).thenReturn(
            Optional.of(AllocationJobTrackingEntity(jobId, UUID.randomUUID(), AllocationJobTrackingState.SUCCEEDED))
        )
        Mockito.`when`(storageProvider.getJobById(jobId)).thenThrow(JobNotFoundException(jobId))
        Mockito.`when`(resultRepository.findById(jobId)).thenReturn(Optional.empty())
        val service = service(storageProvider, resultRepository, tracking)

        assertThrows(IllegalStateException::class.java) { service.getStatusById(jobId) }
    }

    private fun service(
        storageProvider: StorageProvider,
        resultRepository: AllocationJobResultRepository,
        tracking: AllocationJobTrackingService
    ) = AllocationJobRunrService(
        storageProvider,
        resultRepository,
        tracking,
        jacksonObjectMapper().findAndRegisterModules(),
        emptyList()
    )
}

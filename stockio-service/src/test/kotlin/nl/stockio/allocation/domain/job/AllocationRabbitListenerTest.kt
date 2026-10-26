package nl.stockio.allocation.domain.job

import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.AllocationRunType
import nl.stockio.allocation.domain.job.listener.AllocationRabbitListener
import nl.stockio.allocation.domain.processor.AllocationProjectionException
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class AllocationRabbitListenerTest {

    @Test
    fun `enqueues JobRunr fallback when synchronous processing fails`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            asOfDate = LocalDate.now(),
            runType = AllocationRunType.NEW_DEMAND
        )
        val allocationJobExecutor = mock(AllocationJobExecutor::class.java)
        val storageProvider = mock(StorageProvider::class.java)
        val jobScheduler = mock(JobScheduler::class.java)
        doThrow(AllocationProjectionException(UUID.randomUUID(), IllegalArgumentException("Projection is inconsistent")))
            .`when`(allocationJobExecutor)
            .executeSync(request.requestId, request)
        doThrow(JobNotFoundException(request.requestId))
            .`when`(storageProvider)
            .getJobById(request.requestId)

        AllocationRabbitListener(
            jobScheduler = jobScheduler,
            allocationJobExecutor = allocationJobExecutor,
            storageProvider = storageProvider
        ).receive(request)

        val lambda = ArgumentCaptor.forClass(JobLambda::class.java)
        verify(jobScheduler).enqueue(eq(request.requestId), lambda.capture())
        assertThrows(AllocationProjectionException::class.java) {
            lambda.value.run()
        }
        verify(allocationJobExecutor, times(2)).executeSync(request.requestId, request)
    }

    @Test
    fun `rejects invalid request without enqueuing JobRunr fallback`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            asOfDate = LocalDate.now(),
            runType = AllocationRunType.NEW_DEMAND
        )
        val allocationJobExecutor = mock(AllocationJobExecutor::class.java)
        val jobScheduler = mock(JobScheduler::class.java)
        val storageProvider = mock(StorageProvider::class.java)
        doThrow(IllegalArgumentException("Invalid allocation request."))
            .`when`(allocationJobExecutor)
            .executeSync(request.requestId, request)

        assertThrows(IllegalArgumentException::class.java) {
            AllocationRabbitListener(
                jobScheduler = jobScheduler,
                allocationJobExecutor = allocationJobExecutor,
                storageProvider = storageProvider
            ).receive(request)
        }

        verifyNoInteractions(jobScheduler, storageProvider)
    }

    @Test
    fun `does not enqueue a duplicate JobRunr fallback`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            asOfDate = LocalDate.now(),
            runType = AllocationRunType.NEW_DEMAND
        )
        val allocationJobExecutor = mock(AllocationJobExecutor::class.java)
        val jobScheduler = mock(JobScheduler::class.java)
        val storageProvider = mock(StorageProvider::class.java)
        doThrow(IllegalStateException("Synchronous execution failed"))
            .`when`(allocationJobExecutor)
            .executeSync(request.requestId, request)
        `when`(storageProvider.getJobById(request.requestId)).thenReturn(mock(Job::class.java))

        AllocationRabbitListener(
            jobScheduler = jobScheduler,
            allocationJobExecutor = allocationJobExecutor,
            storageProvider = storageProvider
        ).receive(request)

        verifyNoInteractions(jobScheduler)
    }
}

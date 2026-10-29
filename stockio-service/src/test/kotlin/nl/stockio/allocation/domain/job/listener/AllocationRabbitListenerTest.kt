package nl.stockio.allocation.domain.job.listener

import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.AllocationRunType
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.processor.AllocationProjectionException
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.lambdas.JobLambda
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class AllocationRabbitListenerTest {

    @Test
    fun `enqueues JobRunr fallback when synchronous processing fails`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            asOfDate = LocalDate.now(),
            runType = AllocationRunType.NEW_DEMAND
        )
        val allocationJobExecutor = Mockito.mock(AllocationJobExecutor::class.java)
        val storageProvider = Mockito.mock(StorageProvider::class.java)
        val jobScheduler = Mockito.mock(JobScheduler::class.java)
        Mockito.doThrow(
            AllocationProjectionException(
                UUID.randomUUID(),
                IllegalArgumentException("Projection is inconsistent")
            )
        )
            .`when`(allocationJobExecutor)
            .executeSync(request.requestId, request)
        Mockito.doThrow(JobNotFoundException(request.requestId))
            .`when`(storageProvider)
            .getJobById(request.requestId)

        AllocationRabbitListener(
            jobScheduler = jobScheduler,
            allocationJobExecutor = allocationJobExecutor,
            storageProvider = storageProvider
        ).receive(request)

        val lambda = ArgumentCaptor.forClass(JobLambda::class.java)
        Mockito.verify(jobScheduler).enqueue(ArgumentMatchers.eq(request.requestId), lambda.capture())
        Assertions.assertThrows(AllocationProjectionException::class.java) {
            lambda.value.run()
        }
        Mockito.verify(allocationJobExecutor, Mockito.times(2)).executeSync(request.requestId, request)
    }

    @Test
    fun `rejects invalid request without enqueuing JobRunr fallback`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            asOfDate = LocalDate.now(),
            runType = AllocationRunType.NEW_DEMAND
        )
        val allocationJobExecutor = Mockito.mock(AllocationJobExecutor::class.java)
        val jobScheduler = Mockito.mock(JobScheduler::class.java)
        val storageProvider = Mockito.mock(StorageProvider::class.java)
        Mockito.doThrow(IllegalArgumentException("Invalid allocation request."))
            .`when`(allocationJobExecutor)
            .executeSync(request.requestId, request)

        Assertions.assertThrows(IllegalArgumentException::class.java) {
            AllocationRabbitListener(
                jobScheduler = jobScheduler,
                allocationJobExecutor = allocationJobExecutor,
                storageProvider = storageProvider
            ).receive(request)
        }

        Mockito.verifyNoInteractions(jobScheduler, storageProvider)
    }

    @Test
    fun `does not enqueue a duplicate JobRunr fallback`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            asOfDate = LocalDate.now(),
            runType = AllocationRunType.NEW_DEMAND
        )
        val allocationJobExecutor = Mockito.mock(AllocationJobExecutor::class.java)
        val jobScheduler = Mockito.mock(JobScheduler::class.java)
        val storageProvider = Mockito.mock(StorageProvider::class.java)
        Mockito.doThrow(IllegalStateException("Synchronous execution failed"))
            .`when`(allocationJobExecutor)
            .executeSync(request.requestId, request)
        Mockito.`when`(storageProvider.getJobById(request.requestId)).thenReturn(Mockito.mock(Job::class.java))

        AllocationRabbitListener(
            jobScheduler = jobScheduler,
            allocationJobExecutor = allocationJobExecutor,
            storageProvider = storageProvider
        ).receive(request)

        Mockito.verifyNoInteractions(jobScheduler)
    }
}

package nl.stockio.allocation.domain.job

import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.service.AllocationCapacityExceededException
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import org.jobrunr.jobs.Job
import org.jobrunr.jobs.JobDetails
import org.jobrunr.jobs.JobParameter
import org.jobrunr.jobs.filters.JobDefaultFilters
import org.jobrunr.jobs.filters.JobFilterUtils
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.ProcessingState
import org.jobrunr.jobs.states.ScheduledState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.server.BackgroundJobServer
import org.jobrunr.server.BackgroundJobServerConfigurationReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AllocationCapacityRetryFilterTest {
    @Test
    fun `annotation elected filter durably defers the same job without consuming business retries`() {
        val request = AllocationRequest(UUID.randomUUID(), LocalDate.now(), AllocationRunType.NEW_DEMAND)
        val job = Job(UUID.randomUUID(), JobDetails(AllocationJobExecutor::class.java.name, null, "executeSync",
            listOf(JobParameter(UUID.randomUUID()), JobParameter(request))), ProcessingState(UUID.randomUUID(), "test"))
        job.amountOfRetries = 3
        val filters = JobFilterUtils(JobDefaultFilters())
        val server = mockk<BackgroundJobServer>()
        val configuration = mockk<BackgroundJobServerConfigurationReader>()
        every { configuration.id } returns UUID.randomUUID()
        every { configuration.name } returns "test"
        every { server.configuration } returns configuration
        val id = job.id
        repeat(5) {
            val now = Instant.now()
            job.failed("capacity", AllocationCapacityExceededException())
            filters.runOnStateElectionFilter(job)
            assertEquals(id, job.id)
            assertEquals(StateName.SCHEDULED, job.state)
            assertTrue(job.getJobState<ScheduledState>().scheduledAt.isAfter(now.plusSeconds(4)))
            job.enqueue()
            job.startProcessingOn(server)
        }
        repeat(3) {
            job.failed("business failure", IllegalStateException("database failure"))
            filters.runOnStateElectionFilter(job)
            assertEquals(StateName.SCHEDULED, job.state)
            job.enqueue()
            job.startProcessingOn(server)
        }
        job.failed("fourth business failure", IllegalStateException("database failure"))
        filters.runOnStateElectionFilter(job)
        assertEquals(StateName.FAILED, job.state)
    }

    @Test
    fun `persisted capacity exception types remain retryable without reconstructing the exception`() {
        val failed = FailedState("capacity", AllocationCapacityExceededException::class.java.name,
            "busy", null, null, "", false, Instant.now())
        val job = Job(JobDetails("unused", null, "unused", emptyList()), failed)
        job.amountOfRetries = 0
        AllocationCapacityRetryFilter().onStateElection(job, failed)
        assertEquals(StateName.SCHEDULED, job.state)
    }
}

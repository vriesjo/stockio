package nl.stockio.allocation.domain.job

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import java.util.UUID
import nl.stockio.stock.domain.port.AllocationJobTrackingRepository
import nl.stockio.stock.infra.AllocationJobTrackingEntity
import org.junit.jupiter.api.Test

class AllocationJobTrackingServiceTest {
    private val repository = mockk<AllocationJobTrackingRepository>()
    private val service = AllocationJobTrackingService(repository)
    private val jobId = UUID.randomUUID()
    private val requestId = UUID.randomUUID()

    @Test
    fun `redelivery leaves a terminal tracking row unchanged`() {
        listOf(AllocationJobTrackingState.SUCCEEDED, AllocationJobTrackingState.FAILED).forEach { status ->
            every { repository.findById(jobId) } returns Optional.of(
                AllocationJobTrackingEntity(jobId = jobId, requestId = requestId, status = status)
            )

            service.beginProcessing(jobId, requestId)
        }

        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.transition(any(), any(), any(), any()) }
    }

    @Test
    fun `markFailed transitions only from processing`() {
        every {
            repository.transition(jobId, setOf(AllocationJobTrackingState.PROCESSING), AllocationJobTrackingState.FAILED, any())
        } returns 1

        service.markFailed(jobId)

        verify(exactly = 1) {
            repository.transition(jobId, setOf(AllocationJobTrackingState.PROCESSING), AllocationJobTrackingState.FAILED, any())
        }
    }
}

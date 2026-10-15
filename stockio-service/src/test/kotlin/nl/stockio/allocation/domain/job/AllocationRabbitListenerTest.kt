package nl.stockio.allocation.domain.job

import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunType
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class AllocationRabbitListenerTest {

    @Test
    fun `enqueues JobRunr fallback when synchronous processing fails`() {
        val request = AllocationRequest(
            requestId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            asOfDate = LocalDate.now(),
            runType = AllocationRunType.NEW_DEMAND
        )
        val allocationJob = mock(AllocationJob::class.java)
        val allocationEventListener = mock(AllocationEventListener::class.java)
        doThrow(IllegalStateException("Synchronous execution failed"))
            .`when`(allocationJob)
            .execute(request.requestId, request)

        AllocationRabbitListener(allocationJob, allocationEventListener).receive(request)

        verify(allocationEventListener).enqueue(AllocationRequestEvent(request.requestId, request))
    }
}

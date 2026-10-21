package nl.stockio.allocation.domain.job.listener

import nl.stockio.allocation.domain.job.AllocationJob
import nl.stockio.allocation.domain.api.dto.AllocationRequestEvent
import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class AllocationEventListener(
    private val jobScheduler: JobScheduler,
    private val allocationJob: AllocationJob
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun enqueue(event: AllocationRequestEvent) {
        jobScheduler.enqueue(event.jobId) {
            allocationJob.execute(event.jobId, event.request)
        }
    }
}

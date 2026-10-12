package nl.stockio.logistics.service

import org.jobrunr.scheduling.JobScheduler
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class AllocationRequestEventListener(
    private val jobScheduler: JobScheduler,
    private val allocationAndSyncJob: AllocationAndSyncJob
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun enqueue(event: AllocationRequestEvent) {
        jobScheduler.enqueue(event.jobId) {
            allocationAndSyncJob.execute(event.jobId, event.request)
        }
    }
}

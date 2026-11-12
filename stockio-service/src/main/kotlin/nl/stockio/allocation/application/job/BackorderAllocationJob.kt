package nl.stockio.allocation.domain.job

import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.processor.AllocationProcessor
import org.jobrunr.jobs.annotations.Job
import org.springframework.stereotype.Service

/**
 * Job responsible for fulfilling open backorders after an inbound receipt.
 *
 * This job is scheduled to run with a specified number of retries and uses the
 * AllocationCapacityRetryFilter to handle capacity-related failures. It delegates
 * the actual processing of the backorder allocation request to the AllocationProcessor.
 */
@Service
class BackorderAllocationJob(
    private val processingService: AllocationProcessor
) {
    @Job(name = "Fulfill open backorders after inbound receipt", retries = 3,
        jobFilters = [AllocationCapacityRetryFilter::class])
    fun execute(command: BackorderAllocationRequest) {
        processingService.processSync(command)
    }
}

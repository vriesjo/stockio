package nl.stockio.allocation.domain.job

import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.processor.AllocationProcessor
import org.jobrunr.jobs.annotations.Job
import org.springframework.stereotype.Service

@Service
class BackorderAllocationJob(
    private val processingService: AllocationProcessor
) {
    @Job(name = "Fulfill open backorders after inbound receipt", retries = 3,
        jobFilters = [AllocationCapacityRetryFilter::class])
    fun execute(command: BackorderAllocationRequest) {
        processingService.process(command)
    }
}

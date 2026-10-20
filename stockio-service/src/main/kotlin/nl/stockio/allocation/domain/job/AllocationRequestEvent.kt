package nl.stockio.allocation.domain.job

import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationRequest

data class AllocationRequestEvent(
    val jobId: UUID,
    val request: AllocationRequest
)

package nl.stockio.logistics.service

import nl.stockio.logistics.domain.AllocationRequest
import java.util.UUID

data class AllocationRequestEvent(
    val jobId: UUID,
    val request: AllocationRequest
)

package nl.stockio.allocation.domain.processor

import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import org.springframework.stereotype.Service

/**
 * Processor for handling backorder allocation requests.
 *
 * This class is responsible for processing backorder allocation requests by invoking the appropriate services
 * to fulfill the backorders and update stock projections accordingly.
 */
@Service
class BackorderAllocationProcessor(
    private val processor: AllocationProcessor
) {
    fun process(command: BackorderAllocationRequest): AllocationRunResult = processor.process(command)
}

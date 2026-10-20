package nl.stockio.allocation.domain.service

import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.domain.api.data.AllocationRunResult
import nl.stockio.allocation.domain.api.data.AllocationRunType
import nl.stockio.allocation.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service

/**
 * Refreshes forecasts after the allocation workers commit, without synchronizing physical stock.
 * No outer transaction spans the parallel workers or upgrades their shared projection lock.
 * A projection failure is retryable: the allocation service reuses its committed run.
 */
@Service
class AllocationProcessingService(
    private val allocationService: AllocationService,
    private val projectionProcessor: StockProjectionProcessor
) {
    fun process(request: AllocationRequest): AllocationRunResult {
        val run = when (request.runType) {
            AllocationRunType.NEW_DEMAND -> allocationService.allocation(request)
            AllocationRunType.BACKORDER_FULFILLMENT -> allocationService.allocateOpenBackorders(request)
        }
        try {
            projectionProcessor.process(request.asOfDate)
        } catch (exception: IllegalArgumentException) {
            throw AllocationProjectionException(run.runId, exception)
        }
        return run
    }
}

/** Prevents a post-allocation projection invariant failure from being classified as invalid input by RabbitMQ. */
class AllocationProjectionException(runId: UUID, cause: IllegalArgumentException) :
    IllegalStateException("Allocation run '$runId' completed, but projection refresh failed; retry processing.", cause)

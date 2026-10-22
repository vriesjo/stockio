package nl.stockio.allocation.domain.processor

import java.util.UUID
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.AllocationRunType
import nl.stockio.allocation.domain.model.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.allocation.domain.service.BackorderAllocationService
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service

/**
 * Refreshes forecasts after the allocation workers commit, without synchronizing physical stock.
 * No outer transaction spans the parallel workers or upgrades their shared projection lock.
 * A projection failure is retryable: the allocation service reuses its committed run.
 */
@Service
class AllocationProcessor(
    private val allocationService: AllocationService,
    private val projectionProcessor: StockProjectionProcessor,
    private val backorderAllocationService: BackorderAllocationService
) {
    fun process(command: BackorderAllocationRequest): AllocationRunResult {
        val run = backorderAllocationService.allocate(command)
        try {
            projectionProcessor.process(command.asOfDate)
        } catch (exception: IllegalArgumentException) {
            throw AllocationProjectionException(run.runId, exception)
        }
        return run
    }

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

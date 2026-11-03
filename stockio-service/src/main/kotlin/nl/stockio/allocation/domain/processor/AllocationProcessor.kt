package nl.stockio.allocation.domain.processor

import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.allocation.domain.service.BackorderAllocationService
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service

/**
 * Refreshes forecasts after the allocation workers commit, without synchronizing physical stock.
 * No outer transaction spans the parallel workers. Each refresh takes its SKU lock.
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
            projectionProcessor.process(command.asOfDate, run.allocations.map { it.sku } +
                command.skuScope.orEmpty() + command.inboundShipments.flatMap { it.lines }.map { it.sku })
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
            projectionProcessor.process(request.asOfDate, run.allocations.map { it.sku } +
                request.demandOrders.flatMap { it.lines }.map { it.sku } +
                request.inboundShipments.flatMap { it.lines }.map { it.sku })
        } catch (exception: IllegalArgumentException) {
            throw AllocationProjectionException(run.runId, exception)
        }
        return run
    }
}

/** Prevents a post-allocation projection invariant failure from being classified as invalid input by RabbitMQ. */
class AllocationProjectionException(runId: UUID, cause: IllegalArgumentException) :
    IllegalStateException("Allocation run '$runId' completed, but projection refresh failed; retry processing.", cause)

package nl.stockio.allocation.domain.processor

import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.service.BackorderAllocationService
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service

/**
 * Processor for handling backorder allocation requests.
 *
 * This class is responsible for processing backorder allocation requests by invoking the appropriate services
 * to fulfill the backorders and update stock projections accordingly.
 */
@Service
class BackorderAllocationProcessor(
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
}

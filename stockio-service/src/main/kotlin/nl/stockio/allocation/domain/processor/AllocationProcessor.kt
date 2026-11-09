package nl.stockio.allocation.domain.processor

import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.allocation.domain.service.AllocationBackorderService
import nl.stockio.allocation.domain.performance.AllocationAdmission
import nl.stockio.allocation.domain.performance.AllocationLockContentionDetector
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.domain.service.AllocationRequestLimits
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
    private val allocationBackorderService: AllocationBackorderService,
    private val admission: AllocationAdmission,
    private val limits: AllocationRequestLimits
) {
    fun process(command: BackorderAllocationRequest): AllocationRunResult {
        limits.validate(command)
        try {
            return admission.execute(AllocationRunType.BACKORDER_FULFILLMENT) {
                val run = admission.phase("allocation") { allocationBackorderService.allocate(command) }
                project(run) {
                    projectionProcessor.process(command.asOfDate, run.allocations.map { it.sku } +
                        command.skuScope.orEmpty() + command.inboundShipments.flatMap { it.lines }.map { it.sku })
                }
                run
            }
        } catch (exception: Exception) {
            if (AllocationLockContentionDetector.isRetryableLockContention(exception)) {
                throw AllocationLockContentionException(cause = exception)
            }
            throw exception
        }
    }

    fun process(request: AllocationRequest): AllocationRunResult = process(request) { it }

    fun <T> process(request: AllocationRequest, complete: (AllocationRunResult) -> T): T {
        limits.validate(request)
        try {
            return admission.execute(request.runType) {
                val run = admission.phase("allocation") {
                    when (request.runType) {
                        AllocationRunType.NEW_DEMAND -> allocationService.allocation(request)
                        AllocationRunType.BACKORDER_FULFILLMENT -> allocationService.allocateOpenBackorders(request)
                    }
                }
                project(run) {
                    projectionProcessor.process(request.asOfDate, run.allocations.map { it.sku } +
                        request.demandOrders.flatMap { it.lines }.map { it.sku } +
                        request.inboundShipments.flatMap { it.lines }.map { it.sku })
                }
                admission.phase("completion") { complete(run) }
            }
        } catch (exception: Exception) {
            if (AllocationLockContentionDetector.isRetryableLockContention(exception)) {
                throw AllocationLockContentionException(cause = exception)
            }
            throw exception
        }
    }

    private fun project(run: AllocationRunResult, work: () -> Unit) {
        try {
            admission.phase("projection", work)
        } catch (exception: IllegalArgumentException) {
            throw AllocationProjectionException(run.runId, exception)
        }
    }
}

/** Prevents a post-allocation projection invariant failure from being classified as invalid input by RabbitMQ. */
class AllocationProjectionException(runId: UUID, cause: IllegalArgumentException) :
    IllegalStateException("Allocation run '$runId' completed, but projection refresh failed; retry processing.", cause)

package nl.stockio.allocation.domain.processor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.stockio.allocation.application.service.AllocationBackorderService
import nl.stockio.allocation.application.service.AllocationRequestLimits
import nl.stockio.allocation.application.service.AllocationService
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.performance.AllocationAdmission
import nl.stockio.allocation.domain.performance.AllocationLockContentionDetector
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import nl.stockio.util.blockingElastic
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Refreshes forecasts after the allocation workers commit, without synchronizing physical stock.
 * No outer transaction spans the SKU publishers. Each SKU transaction takes its own lock.
 * A projection failure is retryable: the allocation service reuses its committed run.
 *
 * [processSync] is the single blocking boundary for Kafka, RabbitMQ, JobRunr, and MCP.
 */
@Service
class AllocationProcessor(
    private val allocationService: AllocationService,
    private val projectionProcessor: StockProjectionProcessor,
    private val allocationBackorderService: AllocationBackorderService,
    private val admission: AllocationAdmission,
    private val limits: AllocationRequestLimits,
) {
    suspend fun process(command: BackorderAllocationRequest): AllocationRunResult {
        limits.validate(command)
        return execute(AllocationRunType.BACKORDER_FULFILLMENT) {
            val run = admission.phase("allocation") { allocationBackorderService.allocate(command) }
            project(run) {
                projectionProcessor.process(
                    command.asOfDate,
                    run.allocations.map { it.sku } + command.skuScope.orEmpty() +
                        command.inboundShipments.flatMap { it.lines }.map { it.sku },
                )
            }
            run
        }
    }

    suspend fun process(request: AllocationRequest): AllocationRunResult = process(request) { it }

    suspend fun <T> process(
        request: AllocationRequest,
        complete: (AllocationRunResult) -> T,
    ): T {
        limits.validate(request)
        return execute(request.runType) {
            val run =
                admission.phase("allocation") {
                    when (request.runType) {
                        AllocationRunType.NEW_DEMAND -> allocationService.allocation(request)
                        AllocationRunType.BACKORDER_FULFILLMENT -> allocationService.allocateOpenBackorders(request)
                    }
                }
            project(run) {
                projectionProcessor.process(
                    request.asOfDate,
                    run.allocations.map { it.sku } +
                        request.demandOrders.flatMap { it.lines }.map { it.sku } +
                        request.inboundShipments.flatMap { it.lines }.map { it.sku },
                )
            }
            admission.phase("completion") {
                withContext(blockingElastic) { complete(run) }
            }
        }
    }

    fun processSync(command: BackorderAllocationRequest): AllocationRunResult = runBlocking { process(command) }

    fun processSync(request: AllocationRequest): AllocationRunResult = runBlocking { process(request) }

    fun <T> processSync(
        request: AllocationRequest,
        complete: (AllocationRunResult) -> T,
    ): T = runBlocking { process(request, complete) }

    private suspend fun <T> execute(
        runType: AllocationRunType,
        work: suspend () -> T,
    ): T =
        try {
            admission.hold(runType, work)
        } catch (error: Exception) {
            if (error !is AllocationLockContentionException &&
                AllocationLockContentionDetector.isRetryableLockContention(error)
            ) {
                throw AllocationLockContentionException(cause = error)
            }
            throw error
        }

    private suspend fun project(
        run: AllocationRunResult,
        work: () -> Unit,
    ) {
        try {
            admission.phase("projection") {
                withContext(blockingElastic) { work() }
            }
        } catch (exception: IllegalArgumentException) {
            throw AllocationProjectionException(run.runId, exception)
        }
    }
}

/** Prevents a post-allocation projection invariant failure from being classified as invalid input by RabbitMQ. */
class AllocationProjectionException(
    runId: UUID,
    cause: IllegalArgumentException,
) : IllegalStateException("Allocation run '$runId' completed, but projection refresh failed; retry processing.", cause)

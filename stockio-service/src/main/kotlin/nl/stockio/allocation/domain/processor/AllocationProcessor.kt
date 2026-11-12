package nl.stockio.allocation.domain.processor

import java.util.UUID
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.performance.AllocationAdmission
import nl.stockio.allocation.domain.performance.AllocationLockContentionDetector
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.domain.service.AllocationBackorderService
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.allocation.domain.service.AllocationService
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.stock.domain.processor.StockProjectionProcessor
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

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
    private val limits: AllocationRequestLimits
) {
    fun process(command: BackorderAllocationRequest): Mono<AllocationRunResult> =
        Mono.fromCallable { limits.validate(command) }.then(
            execute(AllocationRunType.BACKORDER_FULFILLMENT) {
                admission.phaseMono("allocation", allocationBackorderService.allocate(command)).flatMap { run ->
                    project(run) {
                        projectionProcessor.process(
                            command.asOfDate,
                            run.allocations.map { it.sku } + command.skuScope.orEmpty() +
                                command.inboundShipments.flatMap { it.lines }.map { it.sku }
                        )
                    }.thenReturn(run)
                }
            }
        )

    fun process(request: AllocationRequest): Mono<AllocationRunResult> = process(request) { it }

    fun <T> process(request: AllocationRequest, complete: (AllocationRunResult) -> T): Mono<T> =
        Mono.fromCallable { limits.validate(request) }.then(
            execute(request.runType) {
                val allocation = when (request.runType) {
                    AllocationRunType.NEW_DEMAND -> allocationService.allocation(request)
                    AllocationRunType.BACKORDER_FULFILLMENT -> allocationService.allocateOpenBackorders(request)
                }
                admission.phaseMono("allocation", allocation).flatMap { run ->
                    project(run) {
                        projectionProcessor.process(
                            request.asOfDate,
                            run.allocations.map { it.sku } +
                                request.demandOrders.flatMap { it.lines }.map { it.sku } +
                                request.inboundShipments.flatMap { it.lines }.map { it.sku }
                        )
                    }.then(
                        admission.phaseMono(
                            "completion",
                            Mono.fromCallable { complete(run) }.subscribeOn(Schedulers.boundedElastic())
                        )
                    )
                }
            }
        )

    fun processSync(command: BackorderAllocationRequest): AllocationRunResult = process(command).block()!!

    fun processSync(request: AllocationRequest): AllocationRunResult = process(request).block()!!

    fun <T> processSync(request: AllocationRequest, complete: (AllocationRunResult) -> T): T =
        process(request, complete).block()!!

    private fun <T> execute(runType: AllocationRunType, work: () -> Mono<T>): Mono<T> =
        admission.executeMono(runType, work).onErrorMap(
            { error ->
                error !is AllocationLockContentionException &&
                    AllocationLockContentionDetector.isRetryableLockContention(error)
            },
            { error -> AllocationLockContentionException(cause = error) }
        )

    private fun project(run: AllocationRunResult, work: () -> Unit): Mono<Void> =
        admission.phaseMono(
            "projection",
            Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic())
        ).onErrorMap(IllegalArgumentException::class.java) { AllocationProjectionException(run.runId, it) }
            .then()
}

/** Prevents a post-allocation projection invariant failure from being classified as invalid input by RabbitMQ. */
class AllocationProjectionException(runId: UUID, cause: IllegalArgumentException) :
    IllegalStateException("Allocation run '$runId' completed, but projection refresh failed; retry processing.", cause)

package nl.stockio.allocation.domain.service

import nl.stockio.allocation.domain.api.validator.AllocationRequestValidator
import nl.stockio.allocation.domain.model.AllocationOrder
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.allocation.domain.processor.SkuAllocationProcessor
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.util.requestHash
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Allocates new demand and delegates open-backorder runs.
 *
 * Document registration and run completion stay on JDBC, off the R2DBC transaction.
 * SKU work is a bounded [Flux.flatMap] so a caller does not hold a thread while those transactions run.
 */
@Service
class AllocationService(
    private val allocationRequestValidator: AllocationRequestValidator,
    private val allocationRunLifecycleService: AllocationRunLifecycleService,
    private val skuAllocationProcessor: SkuAllocationProcessor,
    private val allocationDocumentService: AllocationDocumentService,
    private val allocationBackorderService: AllocationBackorderService,
    @param:Value("\${stockio.allocation.max-concurrent-skus:4}")
    private val maxConcurrentSkus: Int
) {

    init {
        require(maxConcurrentSkus > 0) { "The maximum concurrent SKU allocation count must be positive." }
    }

    fun allocation(request: AllocationRequest): Mono<AllocationRunResult> {
        require(request.runType == AllocationRunType.NEW_DEMAND) {
            "Run type must be NEW_DEMAND for allocation."
        }
        return prepare(request).flatMap { prepared ->
            val completed = prepared.result
            if (completed != null) {
                Mono.just(completed)
            } else {
                val run = requireNotNull(prepared.run)
                Flux.fromIterable(prepared.skus)
                    .flatMap({ sku ->
                        skuAllocationProcessor.process(
                            runId = run.id,
                            requestHash = prepared.requestHash,
                            sku = sku,
                            asOfDate = request.asOfDate,
                            orders = prepared.ordersBySku[sku].orEmpty()
                        )
                    }, maxConcurrentSkus)
                    .collectList()
                    .flatMap { allocationsBySku ->
                        Mono.fromCallable {
                            val allocations = orderByRequest(allocationsBySku.flatten(), request)
                            allocationRunLifecycleService.completeRun(run.id, prepared.skus.toSet(), allocations)
                        }.subscribeOn(Schedulers.boundedElastic())
                    }
            }
        }
    }

    fun allocateOpenBackorders(request: AllocationRequest): Mono<AllocationRunResult> {
        require(request.runType == AllocationRunType.BACKORDER_FULFILLMENT) {
            "Run type must be BACKORDER_FULFILLMENT for backorder allocation."
        }
        return Mono.fromCallable {
            allocationRequestValidator.validate(request)
            BackorderAllocationRequest(
                requestId = request.requestId,
                asOfDate = request.asOfDate,
                trigger = BackorderAllocationTrigger.MANUAL,
                inboundShipments = request.inboundShipments
            )
        }.subscribeOn(Schedulers.boundedElastic()).flatMap { allocationBackorderService.allocate(it) }
    }

    private fun prepare(request: AllocationRequest): Mono<PreparedAllocation> =
        Mono.fromCallable {
            existingCompletedRun(request, AllocationRunType.NEW_DEMAND)?.let {
                return@fromCallable PreparedAllocation(result = it)
            }
            allocationRequestValidator.validate(request)
            val requestHash = requestHash(request)
            val run = allocationRunLifecycleService.beginOrResume(request, requestHash)
            if (run.completed) {
                return@fromCallable PreparedAllocation(
                    result = requireNotNull(existingCompletedRun(request, AllocationRunType.NEW_DEMAND))
                )
            }
            allocationDocumentService.initialize(run.id, request)
            val ordersBySku = request.allocationLines().groupBy { it.sku }
            val shipmentsBySku = request.inboundShipments.flatMap { it.lines }.groupBy { it.sku }
            val skus = (ordersBySku.keys + shipmentsBySku.keys).distinct().sorted()
            PreparedAllocation(run = run, requestHash = requestHash, ordersBySku = ordersBySku, skus = skus)
        }.subscribeOn(Schedulers.boundedElastic())

    private fun existingCompletedRun(
        request: AllocationRequest,
        runType: AllocationRunType
    ): AllocationRunResult? {
        val expectedHash = requestHash(request)
        return allocationRunLifecycleService.getRun(request) { previousRun, newRequest ->
            require(previousRun.contractVersion == 2) {
                "Legacy requests cannot be replayed through the multiline contract; read the saved run by ID."
            }
            previousRun.runType == runType &&
                previousRun.requestHash == expectedHash &&
                previousRun.requestHash == requestHash(newRequest)
        }
    }

    private fun orderByRequest(
        allocations: List<AllocationRecord>,
        request: AllocationRequest
    ): List<AllocationRecord> {
        val inputOrder = request.allocationLines().withIndex()
            .associate { it.value.orderLineId to it.index }
        return allocations.sortedWith(
            compareBy<AllocationRecord> { it.priority }
                .thenBy { it.placedDate }
                .thenBy { inputOrder[it.orderLineId] ?: Int.MAX_VALUE }
        )
    }

    private class PreparedAllocation(
        val result: AllocationRunResult? = null,
        val run: AllocationRunEntity? = null,
        val requestHash: String = "",
        val ordersBySku: Map<String, List<AllocationOrder>> = emptyMap(),
        val skus: List<String> = emptyList()
    )
}

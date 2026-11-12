package nl.stockio.allocation.domain.service

import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.port.OpenBackorderRepository
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
 * Fulfills open backorders one SKU at a time on the R2DBC pool.
 * Run lookup, document registration, and completion stay on JDBC.
 */
@Service
class AllocationBackorderService(
    private val allocationRunLifecycleService: AllocationRunLifecycleService,
    private val skuAllocationProcessor: SkuAllocationProcessor,
    private val allocationDocumentService: AllocationDocumentService,
    private val backorderRepository: OpenBackorderRepository,
    @param:Value("\${stockio.allocation.max-concurrent-skus:4}")
    private val maxConcurrentSkus: Int
) {
    init {
        require(maxConcurrentSkus > 0) { "The maximum concurrent SKU allocation count must be positive." }
    }

    fun allocate(allocationRequest: BackorderAllocationRequest): Mono<AllocationRunResult> {
        require(allocationRequest.skuScope == null || allocationRequest.skuScope.all { it.isNotBlank() }) {
            "SKU scope must contain only non-blank SKUs."
        }
        return prepare(allocationRequest).flatMap { prepared ->
            val completed = prepared.result
            if (completed != null) {
                Mono.just(completed)
            } else {
                val run = requireNotNull(prepared.run)
                Flux.fromIterable(prepared.skus)
                    .flatMap({ sku ->
                        skuAllocationProcessor.allocateBackorders(
                            runId = run.id,
                            requestHash = prepared.requestHash,
                            sku = sku,
                            asOfDate = allocationRequest.asOfDate
                        )
                    }, maxConcurrentSkus)
                    .collectList()
                    .flatMap { allocations ->
                        Mono.fromCallable {
                            val ordered = allocations.flatten().sortedWith(
                                compareBy<AllocationRecord> { it.priority }.thenBy { it.placedDate }
                            )
                            allocationRunLifecycleService.completeRun(run.id, prepared.skus.toSet(), ordered)
                        }.subscribeOn(Schedulers.boundedElastic())
                    }
            }
        }
    }

    private fun prepare(allocationRequest: BackorderAllocationRequest): Mono<PreparedBackorderAllocation> =
        Mono.fromCallable {
            val request = AllocationRequest(
                requestId = allocationRequest.requestId,
                asOfDate = allocationRequest.asOfDate,
                runType = AllocationRunType.BACKORDER_FULFILLMENT,
                inboundShipments = allocationRequest.inboundShipments
            )
            val requestHash = requestHash(request)
            allocationRunLifecycleService.getRun(request) { previousRun, newRequest ->
                previousRun.contractVersion == 2 &&
                    previousRun.runType == AllocationRunType.BACKORDER_FULFILLMENT &&
                    previousRun.requestHash == requestHash &&
                    previousRun.requestHash == requestHash(newRequest)
            }?.let { return@fromCallable PreparedBackorderAllocation(result = it) }

            val run = allocationRunLifecycleService.beginOrResume(request, requestHash)
            if (run.completed) {
                return@fromCallable PreparedBackorderAllocation(result = allocationRunLifecycleService.getRun(run.id))
            }
            allocationDocumentService.initialize(run.id, request)
            val operationSkus = allocationRunLifecycleService.getOperationSkus(run.id)
            val skus = (allocationRequest.skuScope ?: backorderRepository.findDistinctSkus().toSet())
                .plus(operationSkus)
                .distinct()
                .sorted()
            PreparedBackorderAllocation(run = run, requestHash = requestHash, skus = skus)
        }.subscribeOn(Schedulers.boundedElastic())

    private class PreparedBackorderAllocation(
        val result: AllocationRunResult? = null,
        val run: AllocationRunEntity? = null,
        val requestHash: String = "",
        val skus: List<String> = emptyList()
    )
}

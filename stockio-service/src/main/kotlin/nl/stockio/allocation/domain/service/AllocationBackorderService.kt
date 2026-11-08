package nl.stockio.allocation.domain.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.AllocationRunType
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.port.OpenBackorderRepository
import nl.stockio.allocation.domain.processor.SkuAllocationProcessor
import nl.stockio.util.requestHash
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Service responsible for handling backorder allocation requests.
 *
 * This service manages the allocation of backorders based on the provided [BackorderAllocationRequest].
 * It validates the request, checks for existing completed runs, and coordinates the allocation process
 * across multiple SKUs using coroutines for concurrent processing.
 */
@Service
class AllocationBackorderService(
    private val allocationRunLifecycleService: AllocationRunLifecycleService,
    private val skuAllocationProcessor: SkuAllocationProcessor,
    private val allocationDocumentService: AllocationDocumentService,
    private val backorderRepository: OpenBackorderRepository,
    @Value("\${stockio.allocation.max-concurrent-skus:4}")
    maxConcurrentSkus: Int
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val allocationDispatcher = Dispatchers.IO.limitedParallelism(maxConcurrentSkus)

    init {
        require(maxConcurrentSkus > 0) { "The maximum concurrent SKU allocation count must be positive." }
    }

    /**
     * Allocates backorders based on the provided [BackorderAllocationRequest].
     *
     * This function validates the request, checks for existing completed runs, and coordinates
     * the allocation process across multiple SKUs using coroutines for concurrent processing.
     *
     * @param allocationRequest The backorder allocation request containing relevant information.
     * @return The result of the backorder allocation run.
     */
    fun allocate(allocationRequest: BackorderAllocationRequest): AllocationRunResult {
        require(allocationRequest.skuScope == null || allocationRequest.skuScope.all { it.isNotBlank() }) {
            "SKU scope must contain only non-blank SKUs."
        }
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
        }?.let { return it }

        val run = allocationRunLifecycleService.beginOrResume(request, requestHash)
        if (run.completed) return allocationRunLifecycleService.getRun(run.id)

        allocationDocumentService.initialize(run.id, request)
        val operationSkus = allocationRunLifecycleService.getOperationSkus(run.id)
        val skus = (allocationRequest.skuScope ?: backorderRepository.findDistinctSkus().toSet())
            .plus(operationSkus)
            .distinct()
            .sorted()
        val allocations = runSkuWorkers(skus) { sku ->
            skuAllocationProcessor.allocateBackorders(
                runId = run.id,
                requestHash = requestHash,
                sku = sku,
                asOfDate = allocationRequest.asOfDate
            )
        }.flatten().sortedWith(compareBy<AllocationRecord> { it.priority }.thenBy { it.placedDate })
        return allocationRunLifecycleService.completeRun(run.id, skus.toSet(), allocations)
    }

    private fun <T> runSkuWorkers(skus: List<String>, action: suspend (String) -> T): List<T> =
        runBlocking {
            coroutineScope {
                skus.map { sku ->
                    async(allocationDispatcher) { action(sku) }
                }.awaitAll()
            }
        }
}

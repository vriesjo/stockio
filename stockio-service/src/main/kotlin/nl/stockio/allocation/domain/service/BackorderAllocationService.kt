package nl.stockio.allocation.domain.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.AllocationRunType
import nl.stockio.allocation.domain.model.AllocationRecord
import nl.stockio.allocation.domain.model.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.worker.AllocationSkuWorker
import nl.stockio.allocation.infra.OpenBackorderJpaRepository
import nl.stockio.util.requestHash
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class BackorderAllocationService(
    private val allocationRunService: AllocationRunService,
    private val allocationSkuWorker: AllocationSkuWorker,
    private val allocationDocumentService: AllocationDocumentService,
    private val backorderRepository: OpenBackorderJpaRepository,
    @Value("\${stockio.allocation.max-concurrent-skus:4}")
    maxConcurrentSkus: Int
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val allocationDispatcher = Dispatchers.IO.limitedParallelism(maxConcurrentSkus)

    init {
        require(maxConcurrentSkus > 0) { "The maximum concurrent SKU allocation count must be positive." }
    }

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
        allocationRunService.getRun(request) { previousRun, newRequest ->
            previousRun.contractVersion == 2 &&
                previousRun.runType == AllocationRunType.BACKORDER_FULFILLMENT &&
                previousRun.requestHash == requestHash &&
                previousRun.requestHash == requestHash(newRequest)
        }?.let { return it }

        val run = allocationRunService.beginOrResume(request, requestHash)
        if (run.completed) return allocationRunService.getRun(run.id)

        allocationDocumentService.initialize(run.id, request)
        val operationSkus = allocationRunService.getOperationSkus(run.id)
        val skus = (allocationRequest.skuScope ?: backorderRepository.findDistinctSkus().toSet())
            .plus(operationSkus)
            .distinct()
            .sorted()
        val allocations = runSkuWorkers(skus) { sku ->
            allocationSkuWorker.allocateBackorders(
                runId = run.id,
                requestHash = requestHash,
                sku = sku,
                asOfDate = allocationRequest.asOfDate
            )
        }.flatten().sortedWith(compareBy<AllocationRecord> { it.priority }.thenBy { it.placedDate })
        return allocationRunService.completeRun(run.id, skus.toSet(), allocations)
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

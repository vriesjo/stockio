package nl.stockio.allocation.domain.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.AllocationRunType
import nl.stockio.util.requestHash
import nl.stockio.allocation.domain.api.validator.AllocationRequestValidator
import nl.stockio.allocation.domain.model.AllocationRecord
import nl.stockio.allocation.domain.model.AllocationRunResult
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.allocation.domain.model.BackorderAllocationTrigger
import nl.stockio.allocation.domain.worker.AllocationSkuWorker
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AllocationService(
    private val allocationRequestValidator: AllocationRequestValidator,
    private val allocationRunService: AllocationRunService,
    private val allocationSkuWorker: AllocationSkuWorker,
    private val allocationDocumentService: AllocationDocumentService,
    private val backorderAllocationService: BackorderAllocationService,
    @Value("\${stockio.allocation.max-concurrent-skus:4}")
    maxConcurrentSkus: Int
) {

    init {
        require(maxConcurrentSkus > 0) { "The maximum concurrent SKU allocation count must be positive." }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val allocationDispatcher = Dispatchers.IO.limitedParallelism(maxConcurrentSkus)

    fun allocation(request: AllocationRequest): AllocationRunResult {
        require(request.runType == AllocationRunType.NEW_DEMAND) {
            "Run type must be NEW_DEMAND for allocation."
        }
        existingCompletedRun(request, AllocationRunType.NEW_DEMAND)?.let { return it }
        allocationRequestValidator.validate(request)

        val requestHash = requestHash(request)
        val run = allocationRunService.beginOrResume(request, requestHash)
        if (run.completed) {
            return requireNotNull(existingCompletedRun(request, AllocationRunType.NEW_DEMAND))
        }

        allocationDocumentService.initialize(run.id, request)
        val ordersBySku = request.allocationLines().groupBy { it.sku }
        val shipmentsBySku = request.inboundShipments.flatMap { it.lines }.groupBy { it.sku }
        val skus = (ordersBySku.keys + shipmentsBySku.keys).distinct().sorted()
        val allocationsBySku = runSkuWorkers(skus) { sku ->
            allocationSkuWorker.allocateNewDemand(
                runId = run.id,
                requestHash = requestHash,
                sku = sku,
                asOfDate = request.asOfDate,
                orders = ordersBySku[sku].orEmpty()
            )
        }
        val allocations = orderByRequest(allocationsBySku.flatten(), request)
        return allocationRunService.completeRun(run.id, skus.toSet(), allocations)
    }

    fun allocateOpenBackorders(request: AllocationRequest): AllocationRunResult {
        require(request.runType == AllocationRunType.BACKORDER_FULFILLMENT) {
            "Run type must be BACKORDER_FULFILLMENT for backorder allocation."
        }
        allocationRequestValidator.validate(request)
        return backorderAllocationService.allocate(
            BackorderAllocationRequest(
                requestId = request.requestId,
                asOfDate = request.asOfDate,
                trigger = BackorderAllocationTrigger.MANUAL,
                inboundShipments = request.inboundShipments
            )
        )
    }

    private fun existingCompletedRun(
        request: AllocationRequest,
        runType: AllocationRunType
    ): AllocationRunResult? {
        val expectedHash = requestHash(request)
        return allocationRunService.getRun(request) { previousRun, newRequest ->
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

    private fun <T> runSkuWorkers(skus: List<String>, action: suspend (String) -> T): List<T> {
        val outcomes = runBlocking {
            coroutineScope {
                skus.map { sku ->
                    async(allocationDispatcher) {
                        try {
                            Result.success(action(sku))
                        } catch (exception: Exception) {
                            Result.failure(exception)
                        }
                    }
                }.awaitAll()
            }
        }
        outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        return outcomes.map { it.getOrThrow() }
    }
}

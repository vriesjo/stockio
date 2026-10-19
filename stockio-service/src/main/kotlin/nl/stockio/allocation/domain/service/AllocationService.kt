package nl.stockio.allocation.domain.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import nl.stockio.allocation.domain.api.bean.AllocationRecord
import nl.stockio.allocation.domain.api.bean.AllocationRequest
import nl.stockio.allocation.domain.api.bean.AllocationRunResult
import nl.stockio.allocation.domain.api.bean.AllocationRunType
import nl.stockio.allocation.infra.OpenBackorderRepository
import nl.stockio.util.requestHash
import nl.stockio.allocation.domain.api.validator.AllocationRequestValidator
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AllocationService(
    private val allocationRequestValidator: AllocationRequestValidator,
    private val allocationRunService: AllocationRunService,
    private val allocationSkuWorker: AllocationSkuWorker,
    private val backorderRepository: OpenBackorderRepository,
    @Value("\${stockio.allocation.max-concurrent-skus:4}")
    maxConcurrentSkus: Int
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val allocationDispatcher = Dispatchers.IO.limitedParallelism(maxConcurrentSkus)

    init {
        require(maxConcurrentSkus > 0) { "The maximum concurrent SKU allocation count must be positive." }
    }

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

        val ordersBySku = request.demandOrders.groupBy { it.sku }
        val shipmentsBySku = request.inboundShipments.groupBy { it.sku }
        val skus = (ordersBySku.keys + shipmentsBySku.keys).distinct().sorted()
        val allocationsBySku = runSkuWorkers(skus) { sku ->
            allocationSkuWorker.allocateNewDemand(
                runId = run.id,
                requestHash = requestHash,
                sku = sku,
                asOfDate = request.asOfDate,
                orders = ordersBySku[sku].orEmpty(),
                shipments = shipmentsBySku[sku].orEmpty()
            )
        }
        val allocations = orderByRequest(allocationsBySku.flatten(), request)
        return allocationRunService.completeRun(run.id, skus.toSet(), allocations)
    }

    fun allocateOpenBackorders(request: AllocationRequest): AllocationRunResult {
        require(request.runType == AllocationRunType.BACKORDER_FULFILLMENT) {
            "Run type must be BACKORDER_FULFILLMENT for backorder allocation."
        }
        existingCompletedRun(request, AllocationRunType.BACKORDER_FULFILLMENT)?.let { return it }
        allocationRequestValidator.validate(request)

        val requestHash = requestHash(request)
        val run = allocationRunService.beginOrResume(request, requestHash)
        if (run.completed) {
            return requireNotNull(existingCompletedRun(request, AllocationRunType.BACKORDER_FULFILLMENT))
        }

        val shipmentsBySku = request.inboundShipments.groupBy { it.sku }
        val priorOperationSkus = allocationRunService.getOperationSkus(run.id)
        val skus = (backorderRepository.findDistinctSkus() + shipmentsBySku.keys + priorOperationSkus)
            .distinct()
            .sorted()
        val allocationsBySku = runSkuWorkers(skus) { sku ->
            allocationSkuWorker.fulfillBackorders(
                runId = run.id,
                requestHash = requestHash,
                sku = sku,
                asOfDate = request.asOfDate,
                shipments = shipmentsBySku[sku].orEmpty()
            )
        }
        val allocations = allocationsBySku.flatten()
            .sortedWith(compareBy<AllocationRecord> { it.priority }.thenBy { it.placedDate })
        return allocationRunService.completeRun(run.id, skus.toSet(), allocations)
    }

    private fun existingCompletedRun(
        request: AllocationRequest,
        runType: AllocationRunType
    ): AllocationRunResult? {
        val expectedHash = requestHash(request)
        return allocationRunService.getRun(request) { previousRun, newRequest ->
            previousRun.runType == runType &&
                previousRun.requestHash == expectedHash &&
                previousRun.requestHash == requestHash(newRequest)
        }
    }

    private fun orderByRequest(
        allocations: List<AllocationRecord>,
        request: AllocationRequest
    ): List<AllocationRecord> {
        val inputOrder = request.demandOrders.withIndex()
            .associate { it.value.orderId to it.index }
        return allocations.sortedWith(
            compareBy<AllocationRecord> { it.priority }
                .thenBy { it.placedDate }
                .thenBy { inputOrder[it.orderId] ?: Int.MAX_VALUE }
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

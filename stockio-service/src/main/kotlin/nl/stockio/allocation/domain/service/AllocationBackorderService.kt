package nl.stockio.allocation.domain.service

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import nl.stockio.util.blockingElastic
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

    suspend fun allocate(allocationRequest: BackorderAllocationRequest): AllocationRunResult {
        require(allocationRequest.skuScope == null || allocationRequest.skuScope.all { it.isNotBlank() }) {
            "SKU scope must contain only non-blank SKUs."
        }
        val prepared = prepare(allocationRequest)
        prepared.result?.let { return it }
        val run = requireNotNull(prepared.run)
        val allocations = Flux.fromIterable(prepared.skus)
            .flatMap({ sku ->
                skuAllocationProcessor.allocateBackorders(
                    runId = run.id,
                    requestHash = prepared.requestHash,
                    sku = sku,
                    asOfDate = allocationRequest.asOfDate
                )
            }, maxConcurrentSkus)
            .collectList()
            .awaitSingle()
        return withContext(blockingElastic) {
            val ordered = allocations.flatten().sortedWith(
                compareBy<AllocationRecord> { it.priority }.thenBy { it.placedDate }
            )
            allocationRunLifecycleService.completeRun(run.id, prepared.skus.toSet(), ordered)
        }
    }

    private suspend fun prepare(allocationRequest: BackorderAllocationRequest): PreparedBackorderAllocation =
        withContext(blockingElastic) {
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
            }?.let { return@withContext PreparedBackorderAllocation(result = it) }

            val run = allocationRunLifecycleService.beginOrResume(request, requestHash)
            if (run.completed) {
                return@withContext PreparedBackorderAllocation(result = allocationRunLifecycleService.getRun(run.id))
            }
            allocationDocumentService.initialize(run.id, request)
            val operationSkus = allocationRunLifecycleService.getOperationSkus(run.id)
            val skus = (allocationRequest.skuScope ?: backorderRepository.findDistinctSkus().toSet())
                .plus(operationSkus)
                .distinct()
                .sorted()
            PreparedBackorderAllocation(run = run, requestHash = requestHash, skus = skus)
        }

    private class PreparedBackorderAllocation(
        val result: AllocationRunResult? = null,
        val run: AllocationRunEntity? = null,
        val requestHash: String = "",
        val skus: List<String> = emptyList()
    )
}

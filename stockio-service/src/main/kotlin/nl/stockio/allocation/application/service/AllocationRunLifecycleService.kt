package nl.stockio.allocation.application.service

import java.time.Instant
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.allocation.infra.AllocationRecordEntity
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.allocation.infra.AllocationSourceEntity
import nl.stockio.api.dto.AllocationRecord
import nl.stockio.api.dto.AllocationRunResult
import nl.stockio.api.dto.AllocationSource
import nl.stockio.allocation.domain.port.outbound.AllocationRecordRepository
import nl.stockio.allocation.domain.port.outbound.AllocationRunRepository
import nl.stockio.allocation.domain.port.outbound.AllocationSkuOperationRepository
import nl.stockio.allocation.domain.port.outbound.AllocationSourceRepository
import nl.stockio.allocation.domain.port.outbound.OpenBackorderRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Manages allocation-run persistence, replay checks, and final result assembly.
 *
 * SKU operations are persisted independently so incomplete runs can resume safely.
 * A run becomes visible as complete only after all expected SKU operations have
 * finished and its allocation records and sources have been saved.
 */
@Component
class AllocationRunLifecycleService(
    private val runRepository: AllocationRunRepository,
    private val skuOperationRepository: AllocationSkuOperationRepository,
    private val backorderRepository: OpenBackorderRepository,
    private val recordRepository: AllocationRecordRepository,
    private val sourceRepository: AllocationSourceRepository
) {

    /**
     * Finds a prior run for [request] and returns its result if it completed.
     *
     * [validateConflict] verifies that the previous run is compatible with the
     * submitted request. Returns `null` when no run exists or the matching run is
     * still incomplete.
     *
     * @throws IllegalArgumentException if the request identity conflicts with the
     * previous run.
     */
    @Transactional(readOnly = true)
    fun getRun(
        request: AllocationRequest,
        validateConflict: (previousRun: AllocationRunEntity, newRequest: AllocationRequest) -> Boolean
    ): AllocationRunResult? {
        return runRepository.findByRequestId(request.requestId)
            ?.let { previousRun ->
                require(validateConflict(previousRun, request)) {
                    "requestId '${request.requestId}' has already been used with a different request."
                }
                if (!previousRun.completed) return@let null
                val runId = previousRun.id
                val run = runRepository.findById(runId)
                    .orElseThrow { IllegalArgumentException("Allocation run '$runId' was not found.") }
                val entities = recordRepository.findAllByRunIdOrderBySequenceNumber(runId)
                val sourcesByRecordId = if (entities.isEmpty()) {
                    emptyMap()
                } else {
                    sourceRepository.findAllByAllocationRecordIdIn(entities.map { it.id })
                        .groupBy { it.allocationRecordId }
                }

                val allocations = entities.map { entity ->
                    AllocationRecord(
                        orderId = entity.orderId,
                        orderLineId = entity.orderLineId,
                        processingOutcome = entity.processingOutcome,
                        customer = entity.customer,
                        sku = entity.sku,
                        priority = entity.priority,
                        placedDate = entity.placedDate,
                        requiredDeliveryDate = entity.requiredDeliveryDate,
                        requestedQuantity = entity.requestedQuantity,
                        allocatedFromInventory = entity.allocatedFromInventory,
                        allocatedFromInbound = entity.allocatedFromInbound,
                        backorderedQuantity = entity.backorderedQuantity,
                        status = entity.status,
                        sources = sourcesByRecordId[entity.id].orEmpty().map {
                            AllocationSource(
                                sourceType = it.sourceType,
                                sourceId = it.sourceId,
                                shipmentId = it.shipmentId,
                                shipmentLineId = it.shipmentLineId,
                                quantity = it.quantity,
                                sourceDate = it.sourceDate,
                                warehouseLocation = it.warehouseLocation
                            )
                        }
                    )
                }

                AllocationRunResult(
                    runId = run.id,
                    requestId = run.requestId,
                    runType = run.runType,
                    asOfDate = run.asOfDate,
                    createdAt = run.createdAt,
                    openBackorderCount = run.openBackorderCount,
                    allocations = allocations
                )
            }
    }

    /**
     * Returns a completed allocation run by its identifier.
     *
     * @throws IllegalArgumentException if no completed run exists for [runId].
     */
    @Transactional(readOnly = true)
    fun getRun(runId: UUID): AllocationRunResult =
        requireNotNull(getRunById(runId)) { "Completed allocation run '$runId' was not found." }

    /**
     * Returns the completed result for [runId], or `null` if it is absent or incomplete.
     */
    @Transactional(readOnly = true)
    fun getRunById(runId: UUID): AllocationRunResult? {
        return runRepository.findById(runId)
            .filter { it.completed }
            .map { run ->
                val entities = recordRepository.findAllByRunIdOrderBySequenceNumber(runId)
                val sourcesByRecordId = if (entities.isEmpty()) {
                    emptyMap()
                } else {
                    sourceRepository.findAllByAllocationRecordIdIn(entities.map { it.id })
                        .groupBy { it.allocationRecordId }
                }

                val allocations = entities.map { entity ->
                    AllocationRecord(
                        orderId = entity.orderId,
                        orderLineId = entity.orderLineId,
                        processingOutcome = entity.processingOutcome,
                        customer = entity.customer,
                        sku = entity.sku,
                        priority = entity.priority,
                        placedDate = entity.placedDate,
                        requiredDeliveryDate = entity.requiredDeliveryDate,
                        requestedQuantity = entity.requestedQuantity,
                        allocatedFromInventory = entity.allocatedFromInventory,
                        allocatedFromInbound = entity.allocatedFromInbound,
                        backorderedQuantity = entity.backorderedQuantity,
                        status = entity.status,
                        sources = sourcesByRecordId[entity.id].orEmpty().map {
                            AllocationSource(
                                sourceType = it.sourceType,
                                sourceId = it.sourceId,
                                shipmentId = it.shipmentId,
                                shipmentLineId = it.shipmentLineId,
                                quantity = it.quantity,
                                sourceDate = it.sourceDate,
                                warehouseLocation = it.warehouseLocation
                            )
                        }
                    )
                }

                AllocationRunResult(
                    runId = run.id,
                    requestId = run.requestId,
                    runType = run.runType,
                    asOfDate = run.asOfDate,
                    createdAt = run.createdAt,
                    openBackorderCount = run.openBackorderCount,
                    allocations = allocations
                )
            }
            .orElse(null)
    }

    /**
     * Creates or resumes the run identified by [request]'s request ID.
     *
     * An existing run is reusable only when its contract version, request hash,
     * run type, and business date match the supplied request.
     *
     * @throws IllegalArgumentException if the request ID was previously used for
     * different request data or an unsupported contract version.
     * @throws IllegalStateException if the run cannot be found after initialization.
     */
    @Transactional
    fun beginOrResume(
        request: AllocationRequest,
        requestHash: String
    ): AllocationRunEntity {
        runRepository.insertIfMissing(
            id = UUID.randomUUID(),
            requestId = request.requestId,
            requestHash = requestHash,
            runType = request.runType.name,
            asOfDate = request.asOfDate,
            createdAt = Instant.now()
        )
        val run = runRepository.findByRequestId(request.requestId)
            ?: throw IllegalStateException("Allocation request '${request.requestId}' could not be initialized.")
        require(
            run.contractVersion == 2 && run.requestHash == requestHash &&
                run.runType == request.runType &&
                run.asOfDate == request.asOfDate
        ) {
            "requestId '${request.requestId}' has already been used with a different request."
        }
        return run
    }

    /**
     * Lists the SKUs with persisted, unfinished operations for [runId].
     */
    @Transactional(readOnly = true)
    fun getOperationSkus(runId: UUID): List<String> =
        skuOperationRepository.findAllByRunId(runId).map { it.sku }

    /**
     * Persists the final allocation records and marks [runId] complete.
     *
     * Completion is allowed only after document initialization and after the
     * persisted SKU-operation set exactly matches [expectedSkus]. Existing completed
     * runs return their saved result, making completion safe to retry.
     *
     * @throws IllegalArgumentException if [runId] does not exist or expected SKU
     * operations are incomplete.
     * @throws IllegalStateException if documents have not been initialized.
     */
    @Transactional
    fun completeRun(
        runId: UUID,
        expectedSkus: Set<String>,
        allocations: List<AllocationRecord>
    ): AllocationRunResult {
        val run = runRepository.lockById(runId)
            ?: throw IllegalArgumentException("Allocation run '$runId' was not found.")
        if (run.completed) return requireNotNull(getRunById(runId))
        check(run.documentsInitialized) { "Allocation run '$runId' has not initialized its documents." }

        val completedSkus = skuOperationRepository.findAllByRunId(runId).map { it.sku }.toSet()
        require(completedSkus == expectedSkus) {
            "Allocation run '$runId' is missing completed SKU operations."
        }

        val recordEntities = allocations.mapIndexed { index, allocation ->
            AllocationRecordEntity(
                runId = run.id,
                sequenceNumber = index,
                orderId = allocation.orderId,
                orderLineId = allocation.orderLineId,
                processingOutcome = allocation.processingOutcome,
                customer = allocation.customer,
                sku = allocation.sku,
                priority = allocation.priority,
                placedDate = allocation.placedDate,
                requiredDeliveryDate = allocation.requiredDeliveryDate,
                requestedQuantity = allocation.requestedQuantity,
                allocatedFromInventory = allocation.allocatedFromInventory,
                allocatedFromInbound = allocation.allocatedFromInbound,
                backorderedQuantity = allocation.backorderedQuantity,
                status = allocation.status
            )
        }
        val savedRecords = recordRepository.saveAll(recordEntities)
        val sourceEntities = savedRecords.zip(allocations).flatMap { (record, allocation) ->
            allocation.sources.map { source ->
                AllocationSourceEntity(
                    allocationRecordId = record.id,
                    sourceType = source.sourceType,
                    sourceId = source.sourceId,
                    shipmentId = source.shipmentId,
                    shipmentLineId = source.shipmentLineId,
                    quantity = source.quantity,
                    sourceDate = source.sourceDate,
                    warehouseLocation = source.warehouseLocation
                )
            }
        }
        sourceRepository.saveAll(sourceEntities)
        run.openBackorderCount = Math.toIntExact(backorderRepository.countDistinctOrders())
        run.completed = true
        skuOperationRepository.deleteAllByRunId(runId)

        return AllocationRunResult(
            runId = run.id,
            requestId = run.requestId,
            runType = run.runType,
            asOfDate = run.asOfDate,
            createdAt = run.createdAt,
            openBackorderCount = run.openBackorderCount,
            allocations = allocations
        )
    }
}

package nl.stockio.allocation.domain.service

import java.time.Instant
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.domain.api.data.AllocationRunResult
import nl.stockio.allocation.domain.api.data.AllocationSource
import nl.stockio.allocation.domain.api.data.AllocationRecord
import nl.stockio.allocation.infra.AllocationRecordEntity
import nl.stockio.allocation.infra.AllocationRecordJpaRepository
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.allocation.infra.AllocationRunJpaRepository
import nl.stockio.allocation.infra.AllocationSkuOperationJpaRepository
import nl.stockio.allocation.infra.AllocationSourceEntity
import nl.stockio.allocation.infra.AllocationSourceJpaRepository
import nl.stockio.allocation.infra.OpenBackorderJpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AllocationRunService(
    private val runRepository: AllocationRunJpaRepository,
    private val skuOperationRepository: AllocationSkuOperationJpaRepository,
    private val backorderRepository: OpenBackorderJpaRepository,
    private val recordRepository: AllocationRecordJpaRepository,
    private val sourceRepository: AllocationSourceJpaRepository
) {

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

    @Transactional(readOnly = true)
    fun getRun(runId: UUID): AllocationRunResult =
        requireNotNull(getRunById(runId)) { "Completed allocation run '$runId' was not found." }

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

    @Transactional(readOnly = true)
    fun getOperationSkus(runId: UUID): List<String> =
        skuOperationRepository.findAllByRunId(runId).map { it.sku }

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

package nl.stockio.allocation.domain.service

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.bean.AllocationRunResult
import nl.stockio.allocation.domain.bean.AllocationRunType
import nl.stockio.allocation.domain.bean.AllocationSource
import nl.stockio.allocation.domain.bean.AllocationRecord
import nl.stockio.allocation.infra.AllocationRecordEntity
import nl.stockio.allocation.infra.AllocationRecordRepository
import nl.stockio.allocation.infra.AllocationRunEntity
import nl.stockio.allocation.infra.AllocationRunRepository
import nl.stockio.allocation.infra.AllocationSourceEntity
import nl.stockio.allocation.infra.AllocationSourceRepository
import nl.stockio.allocation.infra.OpenBackorderRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AllocationRunService(
    private val runRepository: AllocationRunRepository,
    private val backorderRepository: OpenBackorderRepository,
    private val recordRepository: AllocationRecordRepository,
    private val sourceRepository: AllocationSourceRepository
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
    fun getRun(runId: UUID): AllocationRunResult = requireNotNull(getRunById(runId)) { "Allocation run '$runId' was not found." }

    fun getRunById(runId: UUID): AllocationRunResult? {
        return runRepository.findById(runId)
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


    fun persistRun(
        requestId: UUID,
        requestHash: String,
        runType: AllocationRunType,
        asOfDate: LocalDate,
        allocations: List<AllocationRecord>
    ): AllocationRunResult {
        val openBackorderCount = backorderRepository.count().toInt()
        val run = runRepository.save(
            AllocationRunEntity(
                requestId = requestId,
                requestHash = requestHash,
                runType = runType,
                asOfDate = asOfDate,
                openBackorderCount = openBackorderCount,
                createdAt = Instant.now()
            )
        )
        val recordEntities = allocations.mapIndexed { index, allocation ->
            AllocationRecordEntity(
                runId = run.id,
                sequenceNumber = index,
                orderId = allocation.orderId,
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
                    quantity = source.quantity,
                    sourceDate = source.sourceDate,
                    warehouseLocation = source.warehouseLocation
                )
            }
        }
        sourceRepository.saveAll(sourceEntities)

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

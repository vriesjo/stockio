package nl.stockio.allocation.infra.inbound

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.generated.AllocationApi
import nl.stockio.allocation.infra.DisabledAllocationRedisGateway
import nl.stockio.allocation.application.job.AllocationJobRunrService
import nl.stockio.allocation.infra.AllocationRedisSyncDisabledException
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.port.outbound.AllocationGateway
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationJobStatus
import nl.stockio.config.BlockingHttp
import nl.stockio.stock.domain.service.StockLedgerSyncStatus
import nl.stockio.stock.domain.service.StockLedgerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
class AllocationController(
    private val allocationJobExecutor: AllocationJobExecutor,
    private val allocationJobRunrService: AllocationJobRunrService,
    private val ledgerSyncStatusService: StockLedgerService,
    private val clock: Clock,
    private val allocationRedisGateway: AllocationGateway = DisabledAllocationRedisGateway(),
    private val blockingHttp: BlockingHttp = BlockingHttp.immediate()
) : AllocationApi {

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override suspend fun submitAllocationJob(
        request: AllocationRequest,
        async: Boolean
    ): ResponseEntity<AllocationJobAccepted> {
        require(request.asOfDate == LocalDate.now(clock)) {
            "Allocation requests on this endpoint require asOfDate to be today."
        }
        logger.info { "Received allocation request ${request.requestId} (async=$async)" }
        if (allocationRedisGateway.enabled || async) {
            return blockingHttp.await { submitWithoutInlineAllocation(request, async) }
        }
        val syncResult = allocationJobExecutor.execute(UUID.randomUUID(), request)
        val accepted = AllocationJobAccepted(syncResult.allocationRun.runId, request.requestId)
        return ResponseEntity.accepted()
            .location(URI.create("/api/allocation/jobs/${accepted.jobId}"))
            .body(accepted)
    }

    private fun submitWithoutInlineAllocation(
        request: AllocationRequest,
        async: Boolean
    ): ResponseEntity<AllocationJobAccepted> {
        if (allocationRedisGateway.enabled) {
            if (!async) throw AllocationRedisSyncDisabledException()
            allocationRedisGateway.accept(request)
            val accepted = try {
                allocationJobExecutor.executeAsync(request)
            } catch (exception: Exception) {
                allocationRedisGateway.rollback(request)
                throw exception
            }
            return ResponseEntity.accepted()
                .location(URI.create("/api/allocation/jobs/${accepted.jobId}"))
                .body(accepted)
        }
        val accepted = allocationJobExecutor.executeAsync(request)
        return ResponseEntity.accepted()
            .location(URI.create("/api/allocation/jobs/${accepted.jobId}"))
            .body(accepted)
    }

    override suspend fun getAllocationJobStatus(jobId: UUID): ResponseEntity<AllocationJobStatus> =
        blockingHttp.await {
            logger.info { "Fetching allocation job status for jobId=$jobId" }
            ResponseEntity.ok()
                .body(allocationJobRunrService.getStatusById(jobId))
        }

    override suspend fun getAllocationStockLedgerStatus(): ResponseEntity<StockLedgerSyncStatus> =
        blockingHttp.await {
            logger.info { "Fetching stock ledger sync status, last sync run" }
            ResponseEntity.ok()
                .body(ledgerSyncStatusService.getStatusLastSyncRun())
        }
}

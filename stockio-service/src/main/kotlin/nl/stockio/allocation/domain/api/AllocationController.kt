package nl.stockio.allocation.domain.api

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.generated.AllocationApi
import nl.stockio.allocation.infra.DisabledAllocationRedisGateway
import nl.stockio.allocation.domain.service.AllocationJobRunrService
import nl.stockio.allocation.infra.AllocationRedisSyncDisabledException
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.port.AllocationGateway
import nl.stockio.api.dto.AllocationJobAccepted
import nl.stockio.api.dto.AllocationJobStatus
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
    private val allocationRedisGateway: AllocationGateway = DisabledAllocationRedisGateway()
) : AllocationApi {

    override fun submitAllocationJob(
        request: AllocationRequest,
        async: Boolean
    ): ResponseEntity<AllocationJobAccepted> {
        require(request.asOfDate == LocalDate.now(clock)) {
            "Allocation requests on this endpoint require asOfDate to be today."
        }
        logger.info { "Received allocation request ${request.requestId} (async=$async)" }
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
        if (async) {
            val accepted = allocationJobExecutor.executeAsync(request)
            return ResponseEntity.accepted()
                .location(URI.create("/api/allocation/jobs/${accepted.jobId}"))
                .body(accepted)
        } else {
            val syncResult = allocationJobExecutor.executeSync(UUID.randomUUID(), request)
            val accepted = AllocationJobAccepted(syncResult.allocationRun.runId, request.requestId)
            return ResponseEntity.accepted()
                .location(URI.create("/api/allocation/jobs/${accepted.jobId}"))
                .body(accepted)
        }
    }

    override fun getAllocationJobStatus(jobId: UUID): ResponseEntity<AllocationJobStatus> {
        logger.info { "Fetching allocation job status for jobId=$jobId" }
        return ResponseEntity.ok()
            .body(allocationJobRunrService.getStatusById(jobId))
    }

    override fun getAllocationStockLedgerStatus(): ResponseEntity<StockLedgerSyncStatus> {
        logger.info { "Fetching stock ledger sync status, last sync run" }
        return ResponseEntity.ok()
            .body(ledgerSyncStatusService.getStatusLastSyncRun())
    }
}

package nl.stockio.allocation.domain.api

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.OrderReservationResponse
import nl.stockio.reservation.domain.service.OrderReservationService
import nl.stockio.allocation.domain.service.AllocationJobRunrService
import nl.stockio.allocation.domain.job.AllocationJobExecutor
import nl.stockio.allocation.domain.model.AllocationJobAccepted
import nl.stockio.allocation.domain.model.AllocationJobStatus
import nl.stockio.stock.domain.service.StockLedgerSyncStatus
import nl.stockio.stock.domain.service.StockLedgerService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/allocation")
class AllocationController(
    private val allocationJobExecutor: AllocationJobExecutor,
    private val allocationJobRunrService: AllocationJobRunrService,
    private val orderReservationService: OrderReservationService,
    private val ledgerSyncStatusService: StockLedgerService,
    private val clock: Clock
) {

    @PostMapping("/jobs")
    fun allocate(
        @RequestParam(defaultValue = "true") async: Boolean,
        @Valid @RequestBody request: AllocationRequest
    ): ResponseEntity<AllocationJobAccepted> {
        require(request.asOfDate == LocalDate.now(clock)) {
            "Allocation requests on this endpoint require asOfDate to be today."
        }
        logger.info { "Received allocation request ${request.requestId} (async=$async)" }
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

    @GetMapping("/jobs/{jobId}")
    fun allocationStatus(@PathVariable jobId: UUID): ResponseEntity<AllocationJobStatus> {
        logger.info { "Fetching allocation job status for jobId=$jobId" }
        return ResponseEntity.ok()
            .body(allocationJobRunrService.getStatusById(jobId))
    }

    @GetMapping("/stock-ledger/status")
    fun stockLedgerStatus(): ResponseEntity<StockLedgerSyncStatus?> {
        logger.info { "Fetching stock ledger sync status, last sync run" }
        return ResponseEntity.ok()
            .body(ledgerSyncStatusService.getStatusLastSyncRun())
    }

    @GetMapping("/order-reservation/{orderId}/status")
    fun orderReservationStatus(@PathVariable orderId: String): ResponseEntity<OrderReservationResponse> =
        orderReservationService.getOrderReservationStatus(orderId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}

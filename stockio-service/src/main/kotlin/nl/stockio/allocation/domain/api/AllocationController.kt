package nl.stockio.allocation.domain.api

import jakarta.validation.Valid
import java.net.URI
import java.util.UUID
import nl.stockio.allocation.domain.api.data.AllocationJobAccepted
import nl.stockio.allocation.domain.api.data.AllocationJobStatus
import nl.stockio.allocation.domain.api.data.AllocationRequest
import nl.stockio.allocation.domain.api.data.OrderReservationStatus
import nl.stockio.reservation.domain.service.OrderReservationService
import nl.stockio.allocation.domain.job.AllocationJobService
import nl.stockio.allocation.domain.job.AllocationEventPublisher
import nl.stockio.allocation.domain.service.LedgerSyncStatus
import nl.stockio.allocation.domain.service.LedgerSyncStatusService
import org.jobrunr.storage.JobNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/logistics")
class AllocationController(
    private val allocationEventPublisher: AllocationEventPublisher,
    private val allocationJobService: AllocationJobService,
    private val orderReservationService: OrderReservationService,
    private val ledgerSyncStatusService: LedgerSyncStatusService
) {

    @PostMapping("/allocation-jobs")
    fun allocateAndProject(
        @Valid @RequestBody request: AllocationRequest
    ): ResponseEntity<AllocationJobAccepted> {
        val accepted = allocationEventPublisher.publish(request)
        return ResponseEntity.accepted()
            .location(URI.create("/api/logistics/allocation-jobs/${accepted.jobId}"))
            .body(accepted)
    }

    @GetMapping("/allocation-jobs/{jobId}")
    fun allocationJobStatus(@PathVariable jobId: UUID): AllocationJobStatus =
        allocationJobService.getStatus(jobId)

    @GetMapping("/stock-ledger/status")
    fun stockLedgerStatus(): LedgerSyncStatus = ledgerSyncStatusService.getStatus()

    @GetMapping("/orders/{orderId}/reservation")
    fun orderReservationStatus(@PathVariable orderId: String): ResponseEntity<OrderReservationStatus> =
        orderReservationService.getStatus(orderId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid logistics request."))

    @ExceptionHandler(JobNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleUnknownJob(exception: JobNotFoundException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Allocation job not found."))
}

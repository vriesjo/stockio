package nl.stockio.logistics.controller

import jakarta.validation.Valid
import nl.stockio.logistics.domain.AllocationJobAccepted
import nl.stockio.logistics.domain.AllocationJobStatus
import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.service.AllocationJobStatusService
import nl.stockio.logistics.service.LogisticsOrchestrator
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
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/logistics")
class LogisticsAllocationController(
    private val logisticsOrchestrator: LogisticsOrchestrator,
    private val allocationJobStatusService: AllocationJobStatusService
) {

    @PostMapping("/allocate-and-sync")
    fun allocateAndSync(
        @Valid @RequestBody request: AllocationRequest
    ): ResponseEntity<AllocationJobAccepted> {
        val accepted = logisticsOrchestrator.calculateAllocationAndSync(request)
        return ResponseEntity.accepted()
            .location(URI.create("/api/logistics/allocate-and-sync/jobs/${accepted.jobId}"))
            .body(accepted)
    }

    @GetMapping("/allocate-and-sync/jobs/{jobId}")
    fun getAllocationJobStatus(@PathVariable jobId: UUID): AllocationJobStatus =
        allocationJobStatusService.getStatus(jobId)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid logistics request."))

    @ExceptionHandler(JobNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleUnknownJob(exception: JobNotFoundException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Allocation job not found."))
}

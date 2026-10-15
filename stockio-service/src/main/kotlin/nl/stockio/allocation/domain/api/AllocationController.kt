package nl.stockio.allocation.domain.api

import jakarta.validation.Valid
import java.net.URI
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationJobAccepted
import nl.stockio.allocation.domain.bean.AllocationJobStatus
import nl.stockio.allocation.domain.bean.AllocationRequest
import nl.stockio.allocation.domain.job.AllocationJobService
import nl.stockio.allocation.domain.job.AllocationEventPublisher
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
    private val allocationJobService: AllocationJobService
) {

    @PostMapping("/allocate-project-and-sync")
    fun allocateProjectAndSync(
        @Valid @RequestBody request: AllocationRequest
    ): ResponseEntity<AllocationJobAccepted> {
        val accepted = allocationEventPublisher.publish(request)
        return ResponseEntity.accepted()
            .location(URI.create("/api/logistics/allocate-project-and-sync/job-status/${accepted.jobId}"))
            .body(accepted)
    }

    @GetMapping("/allocate-project-and-sync/job-status/{jobId}")
    fun allocateProjectAndSyncJobStatus(@PathVariable jobId: UUID): AllocationJobStatus =
        allocationJobService.getStatus(jobId)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid logistics request."))

    @ExceptionHandler(JobNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleUnknownJob(exception: JobNotFoundException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Allocation job not found."))
}

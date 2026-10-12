package nl.stockio.logistics.controller

import jakarta.validation.Valid
import nl.stockio.logistics.domain.AllocationAndSyncResult
import nl.stockio.logistics.domain.AllocationRequest
import nl.stockio.logistics.service.LogisticsOrchestrator
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/logistics")
class LogisticsAllocationController(
    private val logisticsOrchestrator: LogisticsOrchestrator
) {

    @PostMapping("/allocate-and-sync")
    fun allocateAndSync(
        @Valid @RequestBody request: AllocationRequest
    ): AllocationAndSyncResult =
        logisticsOrchestrator.calculateAllocationAndSync(request)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid logistics request."))
}

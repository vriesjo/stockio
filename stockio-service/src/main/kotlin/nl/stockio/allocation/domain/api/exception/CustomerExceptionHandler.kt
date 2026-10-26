package nl.stockio.allocation.domain.api.exception

import org.jobrunr.storage.JobNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global exception handler for the allocation API.
 *
 * This class handles exceptions thrown by the allocation API controllers and maps them to appropriate HTTP responses.
 * It provides handlers for specific exceptions, such as [IllegalArgumentException] and [JobNotFoundException],
 * returning meaningful error messages and HTTP status codes.
 */
@RestControllerAdvice
class CustomerExceptionHandler {

    // Exception handlers for the controller
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(exception: IllegalArgumentException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Invalid request."))

    @ExceptionHandler(JobNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleUnknownJob(exception: JobNotFoundException): Map<String, String> =
        mapOf("error" to (exception.message ?: "Allocation job not found."))

}

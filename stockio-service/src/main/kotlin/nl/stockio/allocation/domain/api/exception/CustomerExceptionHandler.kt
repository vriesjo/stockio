package nl.stockio.allocation.domain.api.exception

import nl.stockio.allocation.domain.performance.AllocationCapacityExceededException
import nl.stockio.allocation.domain.performance.AllocationLockContentionException
import nl.stockio.allocation.infra.AllocationRedisHoldConflictException
import nl.stockio.allocation.infra.AllocationRedisInsufficientStockException
import nl.stockio.allocation.infra.AllocationRedisNotReadyException
import nl.stockio.allocation.infra.AllocationRedisSyncDisabledException
import nl.stockio.allocation.domain.service.AllocationRequestTooLargeException
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

    @ExceptionHandler(AllocationCapacityExceededException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleCapacity(exception: AllocationCapacityExceededException): Map<String, String> =
        mapOf("code" to "ALLOCATION_CAPACITY_EXHAUSTED", "error" to exception.message.orEmpty())

    @ExceptionHandler(AllocationLockContentionException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleLockContention(exception: AllocationLockContentionException): Map<String, String> =
        mapOf("code" to "ALLOCATION_LOCK_CONTENTION", "error" to exception.message.orEmpty())

    @ExceptionHandler(AllocationRedisInsufficientStockException::class, AllocationRedisHoldConflictException::class, AllocationRedisSyncDisabledException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleFastPathConflict(exception: IllegalStateException): Map<String, String> =
        mapOf(
            "code" to when (exception) {
                is AllocationRedisInsufficientStockException -> "REDIS_INSUFFICIENT_STOCK"
                is AllocationRedisSyncDisabledException -> "REDIS_SYNC_DISABLED"
                is AllocationRedisHoldConflictException -> "REDIS_HOLD_CONFLICT"
                else -> "REDIS_HOLD_CONFLICT"
            },
            "error" to exception.message.orEmpty()
        )

    @ExceptionHandler(AllocationRedisNotReadyException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleRedisNotReady(exception: AllocationRedisNotReadyException): Map<String, String> =
        mapOf("code" to "REDIS_NOT_READY", "error" to exception.message.orEmpty())

    @ExceptionHandler(AllocationRequestTooLargeException::class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    fun handleTooLarge(exception: AllocationRequestTooLargeException): Map<String, String> =
        mapOf("code" to "ALLOCATION_REQUEST_TOO_LARGE", "error" to exception.message.orEmpty())

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

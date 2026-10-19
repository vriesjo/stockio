package nl.stockio.allocation.domain.job

import io.github.oshai.kotlinlogging.KotlinLogging
import nl.stockio.allocation.domain.api.data.AllocationRequest
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class AllocationRabbitListener(
    private val allocationJob: AllocationJob,
    private val allocationEventListener: AllocationEventListener,
    private val storageProvider: StorageProvider
) {

    @RabbitListener(queues = ["\${stockio.allocation.rabbitmq.request-queue}"])
    fun receive(request: AllocationRequest) {
        val jobId = request.requestId
        try {
            allocationJob.execute(jobId, request)
            logger.info { "Processed allocation request '${request.requestId}' synchronously." }
        } catch (exception: IllegalArgumentException) {
            logger.error(exception) {
                "Allocation request '${request.requestId}' is invalid; rejecting it to the dead-letter queue."
            }
            throw exception
        } catch (exception: Exception) {
            logger.error(exception) {
                "Synchronous processing of allocation request '${request.requestId}' failed; enqueueing JobRunr fallback."
            }
            val existingJob = try {
                storageProvider.getJobById(jobId)
            } catch (_: JobNotFoundException) {
                null
            }
            if (existingJob != null) {
                logger.info { "JobRunr job '${jobId}' already exists; skipping enqueue." }
                return
            }
            allocationEventListener.enqueue(AllocationRequestEvent(jobId, request))
        }
    }
}

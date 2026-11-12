package nl.stockio.reservation.application.job

import nl.stockio.reservation.domain.service.ReservationExpiryService
import nl.stockio.reservation.domain.service.ReservationProperties
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.scheduling.JobScheduler
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Job responsible for expiring unconfirmed allocation reservations at regular intervals.
 *
 * This job is scheduled to run recurrently based on the configured expiry interval. It invokes
 * the ReservationExpiryService to process batches of expired reservations until all due reservations
 * have been handled.
 *
 * Only one instance of this job will actually run at a time, as JobRunr ensures that jobs with
 * the same name are not executed concurrently.
 */
@Component
class ReservationExpiryJob(
    private val expiryService: ReservationExpiryService,
    private val properties: ReservationProperties,
    private val jobScheduler: JobScheduler
) {
    @EventListener(ApplicationReadyEvent::class)
    fun register() {
        jobScheduler.scheduleRecurrently("expire-allocation-reservations", properties.expiryInterval) {
            execute()
        }
    }

    @Job(name = "Expire unconfirmed allocation reservations", retries = 3)
    fun execute() {
        do {
            val expiredCount = expiryService.expireBatch()
        } while (expiredCount == properties.expiryBatchSize)
    }
}

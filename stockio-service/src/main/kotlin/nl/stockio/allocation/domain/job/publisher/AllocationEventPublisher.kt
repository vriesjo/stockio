package nl.stockio.allocation.domain.job.publisher

import java.time.LocalDate
import java.time.Clock
import java.util.UUID
import nl.stockio.allocation.domain.api.dto.AllocationRequest
import nl.stockio.allocation.domain.api.dto.AllocationRequestEvent
import nl.stockio.allocation.domain.model.AllocationJobAccepted
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationEventPublisher(
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock
) {

    @Transactional
    fun publish(request: AllocationRequest): AllocationJobAccepted {
        require(request.asOfDate == LocalDate.now(clock)) {
            "Allocation requests on this endpoint require asOfDate to be today."
        }
        val event = AllocationRequestEvent(UUID.randomUUID(), request)
        eventPublisher.publishEvent(event)
        return AllocationJobAccepted(event.jobId)
    }
}

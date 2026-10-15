package nl.stockio.allocation.domain.job

import java.time.LocalDate
import java.util.UUID
import nl.stockio.allocation.domain.bean.AllocationJobAccepted
import nl.stockio.allocation.domain.bean.AllocationRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AllocationEventPublisher(
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun publish(request: AllocationRequest): AllocationJobAccepted {
        require(request.asOfDate == LocalDate.now()) {
            "Allocation and stock synchronization require asOfDate to be today."
        }
        val event = AllocationRequestEvent(UUID.randomUUID(), request)
        eventPublisher.publishEvent(event)
        return AllocationJobAccepted(event.jobId)
    }
}

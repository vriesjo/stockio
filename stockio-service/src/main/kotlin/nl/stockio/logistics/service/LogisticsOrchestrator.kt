package nl.stockio.logistics.service

import nl.stockio.logistics.domain.AllocationJobAccepted
import nl.stockio.logistics.domain.AllocationRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class LogisticsOrchestrator(
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun calculateAllocationAndSync(request: AllocationRequest): AllocationJobAccepted {
        require(request.asOfDate == LocalDate.now()) {
            "Allocation and stock synchronization require asOfDate to be today."
        }
        val event = AllocationRequestEvent(UUID.randomUUID(), request)
        eventPublisher.publishEvent(event)
        return AllocationJobAccepted(event.jobId)
    }
}

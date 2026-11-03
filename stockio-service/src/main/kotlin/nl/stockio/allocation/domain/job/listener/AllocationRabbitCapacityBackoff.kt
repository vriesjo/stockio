package nl.stockio.allocation.domain.job.listener

import java.util.concurrent.ThreadLocalRandom
import org.springframework.amqp.ImmediateRequeueAmqpException
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class AllocationRabbitCapacityBackoff {
    @Volatile
    private var stopping = false

    fun pause() {
        if (stopping) throw ImmediateRequeueAmqpException("Service stopping; retain the allocation message.")
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5_000, 10_001))
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ImmediateRequeueAmqpException("Consumer interrupted; retain the allocation message.", exception)
        }
        if (stopping) throw ImmediateRequeueAmqpException("Service stopping; retain the allocation message.")
    }

    @EventListener(ContextClosedEvent::class)
    fun stop() {
        stopping = true
    }
}

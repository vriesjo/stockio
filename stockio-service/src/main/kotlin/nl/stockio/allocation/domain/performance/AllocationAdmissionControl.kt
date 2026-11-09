package nl.stockio.allocation.domain.performance

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.Gauge
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import nl.stockio.api.dto.AllocationRunType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

@ConfigurationProperties("stockio.allocation.admission")
data class AllocationAdmissionProperties(
    val enabled: Boolean = false,
    val maxInFlight: Int = 0,
    val maxOrders: Int = 0,
    val maxShipments: Int = 0,
    val maxDemandLines: Int = 0,
    val maxInboundLines: Int = 0,
    val maxDistinctSkus: Int = 0,
    val maxRequestBytes: Int = 0
) {
    init {
        if (enabled) {
            require(listOf(maxInFlight, maxOrders, maxShipments, maxDemandLines,
                maxInboundLines, maxDistinctSkus, maxRequestBytes).all { it > 0 }) {
                "Enabled allocation admission requires explicit positive execution, count and byte limits."
            }
            require(maxRequestBytes < Int.MAX_VALUE) { "Allocation request byte limit must be below Int.MAX_VALUE." }
        }
    }
}

@Configuration
@EnableConfigurationProperties(AllocationAdmissionProperties::class)
class AllocationAdmissionConfiguration

class AllocationCapacityExceededException(
    message: String = "ALLOCATION_CAPACITY_EXHAUSTED: This execution attempt was not admitted; retry unchanged later."
) : RuntimeException(message)

@Service
class AllocationAdmission(
    private val properties: AllocationAdmissionProperties,
    private val registry: MeterRegistry
) {
    private val permits = Semaphore(properties.maxInFlight.coerceAtLeast(0))
    private val active = AtomicInteger()

    init {
        registry.gauge("stockio.allocation.admission.active", active)
        Gauge.builder("stockio.allocation.admission.limit", properties) { it.maxInFlight.toDouble() }.register(registry)
        Gauge.builder("stockio.allocation.admission.enabled", properties) { if (it.enabled) 1.0 else 0.0 }.register(registry)
    }

    fun <T> execute(runType: AllocationRunType, work: () -> T): T {
        if (properties.enabled && !permits.tryAcquire()) {
            registry.counter("stockio.allocation.admission.attempts",
                "run.type", runType.name, "outcome", "saturated").increment()
            throw AllocationCapacityExceededException()
        }
        active.incrementAndGet()
        val sample = Timer.start(registry)
        var outcome = "failure"
        try {
            registry.counter("stockio.allocation.admission.attempts",
                "run.type", runType.name, "outcome", "admitted").increment()
            return work().also { outcome = "success" }
        } finally {
            try {
                sample.stop(registry.timer("stockio.allocation.execution.duration",
                    "run.type", runType.name, "outcome", outcome))
            } finally {
                active.decrementAndGet()
                if (properties.enabled) permits.release()
            }
        }
    }

    fun <T> phase(phase: String, work: () -> T): T {
        val sample = Timer.start(registry)
        var outcome = "failure"
        try {
            return work().also { outcome = "success" }
        } finally {
            sample.stop(registry.timer("stockio.allocation.phase.duration",
                "phase", phase, "outcome", outcome))
        }
    }
}

package nl.stockio.allocation.domain.service

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import java.io.OutputStream
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.model.BackorderAllocationRequest
import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.DemandOrder
import nl.stockio.api.dto.InboundShipment
import org.springframework.stereotype.Component

class AllocationRequestTooLargeException :
    RuntimeException("ALLOCATION_REQUEST_TOO_LARGE: Allocation request exceeds the configured byte limit.")

/**
 * Service responsible for validating allocation requests against configured limits.
 *
 * This service checks the number of orders, shipments, distinct SKUs, demand lines,
 * inbound lines, and the total byte size of the request. If any limit is exceeded,
 * an exception is thrown, and a corresponding metric is recorded.
 */
@Component
class AllocationRequestLimits(
    private val properties: AllocationAdmissionProperties,
    private val mapper: ObjectMapper,
    private val registry: MeterRegistry
) {
    fun validate(request: AllocationRequest) {
        validate(request.demandOrders, request.inboundShipments, emptySet(), request)
    }

    // Validates a backorder allocation request by checking its inbound shipments and SKU scope.
    fun validate(command: BackorderAllocationRequest) {
        validate(emptyList(), command.inboundShipments, command.skuScope.orEmpty(), command)
    }

    // Checks if the total byte size of the request exceeds the configured maximum.
    // If it does, a metric is recorded, and an exception is thrown.
    fun checkBytes(bytes: Long) {
        if (properties.enabled && bytes > properties.maxRequestBytes) {
            registry.counter("stockio.allocation.request.rejected", "reason", "bytes").increment()
            throw AllocationRequestTooLargeException()
        }
    }

    private fun validate(
        orders: List<DemandOrder>,
        shipments: List<InboundShipment>,
        scope: Set<String>,
        payload: Any
    ) {
        if (!properties.enabled) return
        checkLimit("orders", orders.size.toLong(), properties.maxOrders)
        checkLimit("shipments", shipments.size.toLong(), properties.maxShipments)
        val skus = mutableSetOf<String>()
        fun addSku(sku: String) {
            skus.add(sku)
            checkLimit("distinct_skus", skus.size.toLong(), properties.maxDistinctSkus)
        }
        scope.forEach(::addSku)
        var demandLines = 0L
        orders.forEach { order ->
            demandLines += order.lines.size
            checkLimit("demand_lines", demandLines, properties.maxDemandLines)
            order.lines.forEach { addSku(it.sku) }
        }
        var inboundLines = 0L
        shipments.forEach { shipment ->
            inboundLines += shipment.lines.size
            checkLimit("inbound_lines", inboundLines, properties.maxInboundLines)
            shipment.lines.forEach { addSku(it.sku) }
        }
        var bytes = 0L
        val counter = object : OutputStream() {
            override fun write(value: Int) = count(1)
            override fun write(buffer: ByteArray, offset: Int, length: Int) = count(length)
            private fun count(length: Int) {
                bytes += length
                checkBytes(bytes)
            }
        }
        try {
            mapper.writeValue(counter, payload)
        } catch (exception: JsonMappingException) {
            var cause: Throwable? = exception
            while (cause != null) {
                if (cause is AllocationRequestTooLargeException) throw cause
                cause = cause.cause
            }
            throw exception
        }
    }

    private fun checkLimit(reason: String, count: Long, limit: Int) {
        if (count > limit) {
            registry.counter("stockio.allocation.request.rejected", "reason", reason).increment()
            throw IllegalArgumentException("ALLOCATION_REQUEST_LIMIT_EXCEEDED: $reason exceeds $limit.")
        }
    }
}

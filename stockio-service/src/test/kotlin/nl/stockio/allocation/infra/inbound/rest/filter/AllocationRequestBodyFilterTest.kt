package nl.stockio.allocation.infra.inbound.rest.filter

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import nl.stockio.allocation.infra.inbound.rest.filter.AllocationRequestBodyFilter
import nl.stockio.allocation.infra.inbound.listener.AllocationRabbitConfiguration
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.application.service.AllocationRequestLimits
import nl.stockio.allocation.application.service.AllocationRequestTooLargeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.support.converter.MessageConversionException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class AllocationRequestBodyFilterTest {
    private val properties = AllocationAdmissionProperties(true, 1, 1, 1, 1, 1, 1, 8)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val registry = SimpleMeterRegistry()
    private val limits = AllocationRequestLimits(properties, mapper, registry)
    private val filter = AllocationRequestBodyFilter(properties, limits, mapper)
    private val buffers = DefaultDataBufferFactory()

    @Test
    fun `unknown content length is bounded before parsing while exact byte boundary is replayed`() {
        for (size in listOf(8, 9)) {
            val exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/allocation/jobs")
                    .body(Flux.just(buffers.wrap(ByteArray(size) { 'x'.code.toByte() })))
            )
            var invoked = false
            filter.filter(exchange) { bounded ->
                invoked = true
                assertEquals(8, bounded.request.headers.contentLength)
                DataBufferUtils.join(bounded.request.body)
                    .doOnNext { buffer ->
                        assertEquals(8, buffer.readableByteCount())
                        DataBufferUtils.release(buffer)
                    }
                    .then()
            }.block()
            assertEquals(size == 8, invoked)
            assertEquals(
                if (size == 8) null else HttpStatus.PAYLOAD_TOO_LARGE,
                exchange.response.statusCode
            )
            if (size == 9) {
                assertTrue(exchange.response.bodyAsString.block()!!.contains("ALLOCATION_REQUEST_TOO_LARGE"))
            }
        }
    }

    @Test
    fun `large declared bodies reject before reading and status reads are unaffected`() {
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/products/allocate")
                .header(HttpHeaders.CONTENT_LENGTH, "9")
                .body(Flux.defer { Mono.error(IllegalStateException("Must not read oversized body")) })
        )
        filter.filter(exchange) { _ -> error("Must not invoke controller") }.block()
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exchange.response.statusCode)

        val status = MockServerWebExchange.from(MockServerHttpRequest.get("/api/allocation/jobs/id").build())
        var invoked = false
        filter.filter(status) { _ ->
            invoked = true
            Mono.empty()
        }.block()
        assertTrue(invoked)
    }

    @Test
    fun `rabbit converter rejects oversized bytes before attempting JSON deserialization`() {
        val converter = AllocationRabbitConfiguration().allocationJsonMessageConverter(mapper, limits)
        val message = Message(ByteArray(9), MessageProperties().also { it.contentType = "application/json" })
        val exception = assertThrows(MessageConversionException::class.java) { converter.fromMessage(message) }
        assertInstanceOf(AllocationRequestTooLargeException::class.java, exception.cause)
    }
}

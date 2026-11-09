package nl.stockio.allocation.domain.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import nl.stockio.allocation.domain.api.filter.AllocationRequestBodyFilter
import nl.stockio.allocation.domain.job.listener.AllocationRabbitConfiguration
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.allocation.domain.service.AllocationRequestTooLargeException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.support.converter.MessageConversionException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AllocationRequestBodyFilterTest {
    private val properties = AllocationAdmissionProperties(true, 1, 1, 1, 1, 1, 1, 8)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val registry = SimpleMeterRegistry()
    private val limits = AllocationRequestLimits(properties, mapper, registry)
    private val filter = AllocationRequestBodyFilter(properties, limits, mapper)

    @Test
    fun `unknown content length is bounded before parsing while exact byte boundary is replayed`() {
        for (size in listOf(8, 9)) {
            val request = object : MockHttpServletRequest("POST", "/api/allocation/jobs") {
                override fun getContentLengthLong(): Long = -1
            }
            request.setContent(ByteArray(size) { 'x'.code.toByte() })
            val response = MockHttpServletResponse()
            var invoked = false
            filter.doFilter(request, response) { bounded, _ ->
                invoked = true
                assertEquals(8, bounded.contentLength)
                assertEquals(8, bounded.inputStream.readAllBytes().size)
            }
            assertEquals(size == 8, invoked)
            assertEquals(if (size == 8) 200 else 413, response.status)
            if (size == 9) assertTrue(response.contentAsString.contains("ALLOCATION_REQUEST_TOO_LARGE"))
        }
    }

    @Test
    fun `large declared bodies reject before reading and status reads are unaffected`() {
        val request = object : MockHttpServletRequest("POST", "/api/products/allocate") {
            override fun getContentLengthLong(): Long = 9
            override fun getInputStream(): jakarta.servlet.ServletInputStream = error("Must not read oversized body")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ -> fail("Must not invoke controller") }
        assertEquals(413, response.status)
        var invoked = false
        filter.doFilter(MockHttpServletRequest("GET", "/api/allocation/jobs/id"), MockHttpServletResponse()) { _, _ ->
            invoked = true
        }
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

package nl.stockio.allocation.domain.api

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import nl.stockio.allocation.domain.service.AllocationAdmissionProperties
import nl.stockio.allocation.domain.service.AllocationRequestLimits
import nl.stockio.allocation.domain.service.AllocationRequestTooLargeException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AllocationRequestBodyFilter(
    private val properties: AllocationAdmissionProperties,
    private val limits: AllocationRequestLimits,
    private val mapper: ObjectMapper
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.enabled || request.method != "POST" ||
            request.requestURI.removePrefix(request.contextPath) !in
            setOf("/api/allocation/jobs", "/api/products/allocate")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val body = try {
            limits.checkBytes(request.contentLengthLong)
            request.inputStream.readNBytes(properties.maxRequestBytes + 1).also {
                limits.checkBytes(it.size.toLong())
            }
        } catch (exception: AllocationRequestTooLargeException) {
            response.status = 413
            response.contentType = "application/json"
            response.setHeader("Cache-Control", "no-store")
            mapper.writeValue(response.outputStream, mapOf(
                "code" to "ALLOCATION_REQUEST_TOO_LARGE", "error" to exception.message
            ))
            return
        }
        chain.doFilter(object : HttpServletRequestWrapper(request) {
            override fun getContentLength(): Int = body.size
            override fun getContentLengthLong(): Long = body.size.toLong()
            override fun getInputStream(): ServletInputStream {
                val input = ByteArrayInputStream(body)
                return object : ServletInputStream() {
                    override fun read(): Int = input.read()
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length)
                    override fun isFinished(): Boolean = input.available() == 0
                    override fun isReady(): Boolean = true
                    override fun setReadListener(listener: ReadListener) {
                        throw UnsupportedOperationException("Allocation bodies use blocking servlet input.")
                    }
                }
            }
            override fun getReader(): BufferedReader =
                BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
        }, response)
    }
}

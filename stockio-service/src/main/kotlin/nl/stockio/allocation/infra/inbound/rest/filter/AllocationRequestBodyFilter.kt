package nl.stockio.allocation.infra.inbound.rest.filter

import com.fasterxml.jackson.databind.ObjectMapper
import nl.stockio.allocation.domain.performance.AllocationAdmissionProperties
import nl.stockio.allocation.application.service.AllocationRequestLimits
import nl.stockio.allocation.application.service.AllocationRequestTooLargeException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AllocationRequestBodyFilter(
    private val properties: AllocationAdmissionProperties,
    private val limits: AllocationRequestLimits,
    private val mapper: ObjectMapper
) : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val path = request.path.pathWithinApplication().value()
        if (!properties.enabled || request.method != HttpMethod.POST || path !in LIMITED_PATHS) {
            return chain.filter(exchange)
        }
        val contentLength = request.headers.contentLength
        if (contentLength >= 0) {
            try {
                limits.checkBytes(contentLength)
            } catch (exception: AllocationRequestTooLargeException) {
                return reject(exchange, exception)
            }
        }
        val cap = (properties.maxRequestBytes + 1).toInt()
        return DataBufferUtils.join(request.body, cap)
            .onErrorMap(DataBufferLimitException::class.java) { AllocationRequestTooLargeException() }
            .map { buffer -> LimitedBody(readAndRelease(buffer)) }
            .defaultIfEmpty(LimitedBody(ByteArray(0)))
            .onErrorResume(AllocationRequestTooLargeException::class.java) { exception ->
                Mono.just(LimitedBody(error = exception))
            }
            .flatMap { limited ->
                val failure = limited.error
                if (failure != null) {
                    return@flatMap reject(exchange, failure)
                }
                val bytes = limited.bytes!!
                try {
                    limits.checkBytes(bytes.size.toLong())
                } catch (exception: AllocationRequestTooLargeException) {
                    return@flatMap reject(exchange, exception)
                }
                chain.filter(exchange.mutate().request(replay(request, bytes)).build())
            }
    }

    private class LimitedBody(val bytes: ByteArray? = null, val error: AllocationRequestTooLargeException? = null)

    private fun reject(exchange: ServerWebExchange, exception: AllocationRequestTooLargeException): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.PAYLOAD_TOO_LARGE
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.cacheControl = "no-store"
        val bytes = mapper.writeValueAsBytes(
            mapOf("code" to "ALLOCATION_REQUEST_TOO_LARGE", "error" to exception.message)
        )
        val buffer = response.bufferFactory().wrap(bytes)
        return response.writeWith(Mono.just(buffer))
    }

    private fun replay(request: ServerHttpRequest, bytes: ByteArray): ServerHttpRequest =
        object : ServerHttpRequestDecorator(request) {
            override fun getHeaders(): HttpHeaders {
                val headers = HttpHeaders()
                headers.putAll(request.headers)
                headers.contentLength = bytes.size.toLong()
                return headers
            }

            override fun getBody(): Flux<DataBuffer> =
                Flux.just(DefaultDataBufferFactory().wrap(bytes))
        }

    private fun readAndRelease(buffer: DataBuffer): ByteArray =
        try {
            val bytes = ByteArray(buffer.readableByteCount())
            buffer.read(bytes)
            bytes
        } finally {
            DataBufferUtils.release(buffer)
        }

    private companion object {
        val LIMITED_PATHS = setOf("/api/allocation/jobs", "/api/products/allocate")
    }
}

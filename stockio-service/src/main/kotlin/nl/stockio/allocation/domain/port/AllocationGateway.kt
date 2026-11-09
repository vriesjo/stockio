package nl.stockio.allocation.domain.port

import nl.stockio.api.dto.AllocationRequest
import nl.stockio.api.dto.RedisKeyResponse

interface AllocationGateway {
    val enabled: Boolean
    fun accept(request: AllocationRequest)
    fun rollback(request: AllocationRequest)
    fun settle(request: AllocationRequest)
    fun getByKey(key: String): RedisKeyResponse?
}

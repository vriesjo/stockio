package nl.stockio.api.dto

data class RedisKeyResponse(
    val key: String,
    val value: String? = null,
    val fields: Map<String, String>? = null,
    val ttlSeconds: Long? = null
)

package nl.stockio.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import java.security.MessageDigest

// Configured ObjectMapper for Kotlin & Java 8 Dates (LocalDate, UUID, etc.))
val objectMapper: ObjectMapper = ObjectMapper().apply {
    registerModule(KotlinModule.Builder().build())
    registerModule(JavaTimeModule())
}

// Hash function using reified type for robust generic serialization
inline fun <reified T> requestHash(value: T): String {
    val bytes = objectMapper.writerFor(jacksonTypeRef<T>()).writeValueAsBytes(value)
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

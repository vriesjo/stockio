package nl.stockio.util

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest

    inline fun <reified T> requestHash(value: T): String =
        MessageDigest.getInstance("SHA-256")
            .digest(ObjectMapper().writeValueAsBytes(value))
            .joinToString("") { "%02x".format(it) }

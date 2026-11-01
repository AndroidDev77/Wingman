package com.wingman.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Reads unverified claims from a JWT. The client never trusts these for authorization (the
 * backend verifies the signature); they only schedule refreshes and reject obviously bad pastes.
 */
object Jwt {
    private val json = Json { ignoreUnknownKeys = true }

    /** The payload of a three-part compact JWS, or null if [token] is not one. */
    fun payload(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        return try {
            val bytes = Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** `exp` in epoch milliseconds, or null. */
    fun expiryEpochMs(token: String): Long? =
        ((payload(token)?.get("exp") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull)?.let { it * 1000 }
}

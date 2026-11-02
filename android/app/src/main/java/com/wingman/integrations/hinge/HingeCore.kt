package com.wingman.integrations.hinge

import java.security.MessageDigest
import java.util.UUID

/*
 * WING-029: experimental Hinge connector (D-021, D-023, ADR 0008). Shared primitives.
 *
 * Everything in this package (outside `android/`) is PURE KOTLIN: no android.* imports, so it is
 * JVM-tested with fakes (MockWebServer, a fake socket). Nothing in it has run against Hinge or
 * Sendbird. Every capability is UNVERIFIED until the owner's live test (docs/integrations/hinge.md).
 *
 * Protocol reference: f0rr0/hinge-rs @ cffe397 (MIT OR Apache-2.0), reimplemented from the
 * protocol description, not copied. See THIRD_PARTY_NOTICES.md in this directory.
 *
 * Privacy: message text, phone numbers, one-time codes, Hinge and Sendbird tokens, Sendbird
 * session keys and match IDs are private. Nothing here logs them; [HingeLog] receives event names
 * and fixed-vocabulary fields only, and `toString()` of every type that holds them redacts them.
 */

/** Contract platform key. */
const val HINGE_PLATFORM: String = "hinge"

/** `connector_capabilities.v1.connector_version` of this implementation. */
const val HINGE_CONNECTOR_VERSION: String = "0.1.0"

/** The protocol reference this implementation follows. Recorded in the live-test results. */
const val HINGE_REFERENCE_COMMIT: String = "f0rr0/hinge-rs@cffe397"

/** Wall clock, injectable for deterministic tests. */
fun interface HingeClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = HingeClock { System.currentTimeMillis() }
    }
}

/** Blocking sleep, injectable so tests advance a fake clock instead of waiting. */
fun interface HingeSleeper {
    fun sleep(millis: Long)

    companion object {
        val SYSTEM = HingeSleeper { if (it > 0) Thread.sleep(it) }
    }
}

/** Random source for jitter, injectable for deterministic tests. */
fun interface HingeRandom {
    /** Uniform in [from, until] (inclusive). */
    fun nextLong(from: Long, until: Long): Long

    companion object {
        val SYSTEM = HingeRandom { from, until -> if (until <= from) from else java.util.concurrent.ThreadLocalRandom.current().nextLong(from, until + 1) }
    }
}

/**
 * Structured, redacted log sink. Callers pass an event name and fields from a fixed vocabulary:
 * enums, counts, HTTP status codes, stop-reason codes and short hashes. NEVER message text, phone
 * numbers, codes, tokens, session keys, channel URLs or user IDs (the privacy tests scan every
 * line for sentinels).
 */
fun interface HingeLog {
    fun log(event: String, fields: Map<String, String>)

    companion object {
        val NONE = HingeLog { _, _ -> }
    }
}

/** Hashing helpers. A hash is safe to log and to use as a store key; its input is not. */
object HingeHashes {
    fun sha256Hex(vararg parts: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            val bytes = (it ?: "").toByteArray(Charsets.UTF_8)
            digest.update((if (it == null) "n" else bytes.size.toString()).toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 16 hex chars, for log fields and store keys. */
    fun short(value: String?): String = sha256Hex(value).take(16)

    /** Deterministic `connector_event.v1.event_id` (name-based UUID). */
    fun eventId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(("wingman.hinge.event|" + parts.joinToString("|")).toByteArray(Charsets.UTF_8)).toString()
}

/** "<redacted N chars>" for a private value in a toString(). */
internal fun redacted(value: String?): String = if (value == null) "null" else "<redacted ${value.length} chars>"

/**
 * Raised (as an IOException so it travels through OkHttp interceptors) when a Hinge or Sendbird
 * call is refused BEFORE it reaches the network: experimental flag off, not opted in, circuit
 * breaker open, endpoint not allowlisted, or a local rate limit. [code] is a fixed string, safe to log.
 */
open class HingeCallRefused(val code: String) : java.io.IOException("hinge call refused: $code") {
    companion object {
        const val FLAG_OFF = "experimental_flag_off"
        const val NOT_CONFIGURED = "not_configured"
        const val NOT_OPTED_IN = "not_opted_in"
        const val STOPPED = "circuit_breaker_open"
        const val NOT_ALLOWLISTED = "endpoint_not_allowlisted"
        const val RATE_LIMITED = "local_rate_limit"
    }
}

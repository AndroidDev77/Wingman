package com.wingman.core.api

import java.util.UUID

/**
 * `Idempotency-Key` header value (ADR 0004): 8 to 128 chars of `[A-Za-z0-9._:-]`.
 * Generate ONE per user action and reuse it for every retry of that action.
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Idempotency-Key must match ${PATTERN.pattern}" }
    }

    override fun toString(): String = value

    companion object {
        const val HEADER = "Idempotency-Key"
        val PATTERN = Regex("^[A-Za-z0-9._:-]{8,128}$")

        /** Fresh random UUIDv4, as the contract recommends. */
        fun random(): IdempotencyKey = IdempotencyKey(UUID.randomUUID().toString())
    }
}

/**
 * Holds one key per logical user action so that retries reuse it and rapid repeat taps never
 * mint a second key (WING-015). Action IDs are app-defined strings such as `approve:<approvalId>`.
 *
 * Lifecycle: [keyFor] returns the same key until [complete] is called. Call [complete] once the
 * server has given a final answer (2xx or a non-retryable 4xx); after that, the next attempt of
 * the same action is a NEW action and gets a new key. Keep the key on retryable failures.
 *
 * Thread-safe. Lives in a ViewModel so it survives configuration changes.
 */
class IdempotencyKeyRegistry(private val generate: () -> IdempotencyKey = IdempotencyKey::random) {
    private val keys = HashMap<String, IdempotencyKey>()

    @Synchronized
    fun keyFor(actionId: String): IdempotencyKey = keys.getOrPut(actionId, generate)

    @Synchronized
    fun complete(actionId: String) {
        keys.remove(actionId)
    }

    @Synchronized
    fun pending(): Set<String> = keys.keys.toSet()
}

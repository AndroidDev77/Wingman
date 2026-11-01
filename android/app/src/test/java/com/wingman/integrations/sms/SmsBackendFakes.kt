package com.wingman.integrations.sms

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.ConnectorEventIngestOutcome
import com.wingman.core.model.ConnectorEventIngestResult
import com.wingman.core.model.OutboxAuthorization
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxClaimRequest
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.OutboxInstruction
import com.wingman.core.model.OutboxInstructionState
import com.wingman.core.model.Problem
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Instant

/** A scripted backend: each call takes the next scripted answer (or a default). Records every request. */
class ScriptedSmsBackend : WingmanApi by MockWingmanApi() {
    data class Ingest(val connectionId: String, val envelope: JsonObject, val key: String)
    data class Claim(val connectionId: String, val body: OutboxClaimRequest, val key: String)

    val ingests = mutableListOf<Ingest>()
    val claims = mutableListOf<Claim>()
    val ingestScript = ArrayDeque<(Ingest) -> ApiResult<ConnectorEventIngestResult>>()
    val claimScript = ArrayDeque<(Claim) -> ApiResult<OutboxClaimResult>>()

    /** Default ingest answer: 202 accepted. */
    var defaultIngest: (Ingest) -> ApiResult<ConnectorEventIngestResult> = { accepted(it.key) }

    override suspend fun ingestConnectorEvent(connectionId: String, envelope: JsonObject, key: IdempotencyKey): ApiResult<ConnectorEventIngestResult> {
        val req = Ingest(connectionId, envelope, key.value)
        ingests += req
        return (ingestScript.removeFirstOrNull() ?: defaultIngest)(req)
    }

    override suspend fun claimOutboxInstructions(connectionId: String, body: OutboxClaimRequest, key: IdempotencyKey): ApiResult<OutboxClaimResult> {
        val req = Claim(connectionId, body, key.value)
        claims += req
        return (claimScript.removeFirstOrNull() ?: { ApiResult.Success(OutboxClaimResult(emptyList(), SERVER_NOW)) })(req)
    }

    companion object {
        val SERVER_NOW: Instant = Instant.parse("2026-10-31T19:05:00Z")

        fun accepted(eventId: String, outcome: ConnectorEventIngestOutcome = ConnectorEventIngestOutcome.ACCEPTED) =
            ApiResult.Success(ConnectorEventIngestResult(eventId, outcome, null, SERVER_NOW), idempotentReplayed = outcome == ConnectorEventIngestOutcome.DUPLICATE_EVENT)

        fun http(status: Int, code: String? = null, retryAfterSeconds: Long? = null): ApiResult.Failure = ApiResult.Failure(
            ApiError.Http(
                status = status,
                problem = code?.let { Problem("https://wingman.invalid/problems/$it", it, status, it) },
                retryAfterSeconds = retryAfterSeconds,
            ),
        )

        fun network(): ApiResult.Failure = ApiResult.Failure(ApiError.Network(IOException("offline")))
    }
}

/** A leased instruction for [CONNECTION], like `contracts/examples/outbox.claim.response.json`. */
fun instruction(
    outbound: String = OUTBOUND,
    attempt: Int = 1,
    text: String = "Yes! 8pm?",
    thread: String = "sms:+15550101234",
    leaseSeconds: Long = 120,
    serverNow: Instant = ScriptedSmsBackend.SERVER_NOW,
    authorization: OutboxAuthorization = OutboxAuthorization(OutboxAuthorizationOrigin.APPROVED_DRAFT, "0192f3a0-0000-7000-8000-0000000000e3"),
    connectionId: String = CONNECTION,
    state: OutboxInstructionState = OutboxInstructionState.LEASED,
) = OutboxInstruction(
    id = "0192f3a0-0000-7000-8000-000000000401",
    connectionId = connectionId,
    outboundMessageId = outbound,
    idempotencyKey = "send:$outbound:$attempt",
    attempt = attempt,
    state = state,
    createdAt = serverNow.minusSeconds(2),
    leasedAt = serverNow,
    leaseExpiresAt = serverNow.plusSeconds(leaseSeconds),
    conversationId = "0192f3a0-0000-7000-8000-0000000000b2",
    externalThreadId = thread,
    text = text,
    authorization = authorization,
)

fun claimResult(vararg instructions: OutboxInstruction, serverNow: Instant = ScriptedSmsBackend.SERVER_NOW) =
    OutboxClaimResult(instructions.toList(), serverNow)

package com.wingman.integrations.sms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

const val USER = "0192f3a0-0000-7000-8000-000000000001"
const val CONNECTION = "0192f3a0-0000-7000-8000-0000000000a1"
const val OUTBOUND = "0192f3a0-0000-7000-8000-000000000400"
val SCOPE = SmsConnectionScope(USER, CONNECTION)

/** Settable clock. */
class FakeClock(var now: Long = 1_793_473_200_000L) : SmsClock {
    override fun nowMillis(): Long = now
}

/** Records every submit; [parts] decides the split, [throwOnSubmit] simulates a synchronous refusal. */
class FakeSmsTransport(var parts: Int = 1, var throwOnSubmit: Exception? = null) : SmsTransport {
    data class Submit(val destination: String, val text: String, val subscriptionId: Int?, val key: String, val deliveryReports: Boolean)

    val submits = mutableListOf<Submit>()

    override fun submit(destination: String, text: String, subscriptionId: Int?, idempotencyKey: String, requestDeliveryReports: Boolean): Int {
        submits += Submit(destination, text, subscriptionId, idempotencyKey, requestDeliveryReports)
        throwOnSubmit?.let { throw it }
        return parts
    }

    fun count(key: String) = submits.count { it.key == key }
}

/** Test double for [SmsEnvelopeUploader]: records accepted envelopes; [next] picks the result. */
class FakeSmsEnvelopeUploader(var next: (JsonObject) -> SmsUploadResult = { SmsUploadResult.Accepted }) : SmsEnvelopeUploader {
    val uploaded = mutableListOf<JsonObject>()
    var calls = 0

    override fun upload(envelope: JsonObject): SmsUploadResult {
        calls++
        val result = next(envelope)
        if (result == SmsUploadResult.Accepted) uploaded += envelope
        return result
    }
}

fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.let { if (it.isString) it.content else null }

fun sms(
    from: String = "+1 (555) 010-1234",
    body: String = "Hey! Free tonight?",
    smscTs: Long = 1_793_473_200_000L,
    receivedAt: Long = 1_793_473_201_500L,
    sub: Int? = 1,
    parts: Int = 1,
) = IncomingSms(from, body, smscTs, receivedAt, sub, parts)

fun sendCommand(
    key: String = "send:$OUTBOUND",
    text: String = "Yes! 8pm?",
    thread: String = "sms:+15550101234",
    outbound: String = OUTBOUND,
) = SmsSendCommand(outbound, key, thread, text, authorizedBy = "approval:0192f3a0-0000-7000-8000-000000000500")

val READY = SmsSendPreconditions(hasTelephony = true, sendPermissionGranted = true)

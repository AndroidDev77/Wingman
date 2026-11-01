package com.wingman.integrations.sms

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanJson
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.OutboxClaimRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Produces one envelope of every type this connector emits, and one capabilities document,
 * and checks their key sets against the frozen contract example. When the system property
 * `wingman.smsFixturesDir` is set, it also writes them there so an external JSON-Schema
 * validator can check them against the JSON Schemas in `contracts/schemas/`
 * (see docs/integrations/android_sms.md, "Schema validation of emitted envelopes").
 */
class SmsContractFixturesTest {
    private val clock = FakeClock()

    private fun fixtures(): Map<String, JsonObject> {
        val store = InMemorySmsStateStore()
        val outbox = SmsOutbox(store)
        val f = SmsEnvelopeFactory(SCOPE)
        val out = linkedMapOf<String, JsonObject>()
        out["event.message_received"] = f.received(sms())
            .json
        out["event.message_received_alnum_sender"] = f.received(sms(from = "ACME Bank", sub = null, body = ""))
            .json

        val t = FakeSmsTransport()
        val s = SmsSender(SCOPE, store, t, outbox, clock)
        s.send(sendCommand(), READY)
        s.onPartSent("send:$OUTBOUND", 0, SmsSentResult.Ok)
        s.onPartDelivered("send:$OUTBOUND", 0, SmsDeliveryStatus.DELIVERED)
        s.send(sendCommand(key = "send:$OUTBOUND:2"), READY)
        s.onPartSent("send:$OUTBOUND:2", 0, SmsSentResult.RadioOff)
        s.send(sendCommand(key = "send:$OUTBOUND:3"), READY)
        clock.now += 10 * 60 * 1000
        s.expireStale()
        outbox.pending().forEachIndexed { i, e -> out["event.outbox_${i}_${e.str("type")!!.replace('.', '_')}"] = e }

        // Contract v1.1.0 path: envelopes produced by executing claimed instructions (SmsOutboxPoller).
        val pStore = InMemorySmsStateStore()
        val pOutbox = SmsOutbox(pStore)
        val pSender = SmsSender(SCOPE, pStore, FakeSmsTransport(), pOutbox, clock)
        val backend = ScriptedSmsBackend()
        val poller = SmsOutboxPoller(SCOPE, pStore, backend, pSender, clock)
        backend.claimScript += { ApiResult.Success(claimResult(instruction(attempt = 1), instruction(attempt = 2, outbound = OUTBOUND_B))) }
        poller.poll(READY)
        pSender.onPartSent("send:$OUTBOUND:1", 0, SmsSentResult.Ok)
        pSender.onPartDelivered("send:$OUTBOUND:1", 0, SmsDeliveryStatus.DELIVERED)
        pSender.onPartSent("send:$OUTBOUND_B:2", 0, SmsSentResult.NoService)
        backend.claimScript += { clock.now += 200_000; ApiResult.Success(claimResult(instruction(attempt = 3))) }
        poller.poll(READY)
        pOutbox.pending().forEachIndexed { i, e -> out["event.poller_${i}_${e.str("type")!!.replace('.', '_')}"] = e }
        out["request.outbox_claim"] = WingmanJson.encodeToJsonElement(OutboxClaimRequest.serializer(), backend.claims.first().body).jsonObject

        val device = SmsDeviceState(true, true, true, true, true)
        val caps = SmsCapabilities.build(device, clock.now)
        out["capabilities.sms_default"] = caps
        out["capabilities.sms_no_role"] = SmsCapabilities.build(device.copy(isDefaultSmsApp = false, sendSmsGranted = false), clock.now)
        out["capabilities.sms_verified"] = SmsCapabilities.build(device, clock.now, SmsDeviceVerification("test-only evidence string", clock.now), SmsDeviceVerification("test-only evidence string", clock.now))
        out["capabilities.sms_no_telephony"] = SmsCapabilities.build(device.copy(hasTelephony = false), clock.now)
        out["event.capabilities_changed"] = f.capabilitiesChanged(caps, clock.now).json
        return out
    }

    @Test fun `emits every event type`() {
        val types = fixtures().filterKeys { it.startsWith("event.") }.values.map { it.str("type") }.toSet()
        assertEquals(
            setOf("message.received", "message.send_acknowledged", "message.delivery_receipt", "message.send_failed", "connection.capabilities_changed"),
            types,
        )
    }

    @Test fun `received envelope has the same shape as the frozen sms example`() {
        val example = Json.parseToJsonElement(File(ContractFiles.examplesDir(), "event.sms_message_received.json").readText()).jsonObject
        val ours = fixtures().getValue("event.message_received")
        assertEquals(example.keys, ours.keys)
        assertEquals(example["payload"]!!.jsonObject.keys, ours["payload"]!!.jsonObject.keys)
    }

    @Test fun `default capabilities match the frozen sms example statuses`() {
        val example = Json.parseToJsonElement(File(ContractFiles.examplesDir(), "capabilities.sms.json").readText()).jsonObject
        val ours = fixtures().getValue("capabilities.sms_default")
        val names = listOf("read", "send", "swipe", "calendar_write", "draft")
        for (n in names) {
            assertEquals(n, example["capabilities"]!!.jsonObject[n]!!.jsonObject.str("status"), ours["capabilities"]!!.jsonObject[n]!!.jsonObject.str("status"))
        }
    }

    @Test fun `claimed instructions produce ack, receipt, failure and lease-timeout envelopes`() {
        val poller = fixtures().filterKeys { it.startsWith("event.poller_") }
        assertEquals(
            listOf("message.send_acknowledged", "message.delivery_receipt", "message.send_failed", "message.send_failed"),
            poller.values.map { it.str("type") },
        )
        val timeout = poller.values.last()["payload"]!!.jsonObject
        assertEquals("send:$OUTBOUND:3", timeout.str("idempotency_key"))
        assertEquals("timeout", timeout["failure"]!!.jsonObject.str("code"))
    }

    @Test fun `write fixtures for external schema validation`() {
        val dir = System.getProperty("wingman.smsFixturesDir")?.takeIf { it.isNotBlank() } ?: return
        val d = File(dir).apply { mkdirs() }
        d.listFiles()?.forEach { it.delete() }
        fixtures().forEach { (name, json) -> File(d, "$name.json").writeText(json.toString()) }
        assertTrue(d.listFiles()!!.size >= 15)
    }

    private companion object {
        const val OUTBOUND_B = "0192f3a0-0000-7000-8000-000000000402"
    }
}

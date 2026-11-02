package com.wingman.integrations.hinge

import com.wingman.core.model.ContractFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HingeMappingAndAllowlistTest {
    private val mapper = HingeEventMapper(USER_ID, CONNECTION_ID)

    private fun msg(type: String = "MESG", custom: String = "", text: String = Sentinels.TEXT, sender: String = Sentinels.MATCH_ID, removed: Boolean = false) =
        SendbirdMessage("777", type, custom, text, T0, sender, "Riley", Sentinels.CHANNEL, removed, type == "FILE")

    private fun payload(e: HingeEnvelope) = e.json["payload"]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun `a match's text message is a linked chat_message with exact platform ids`() {
        val e = mapper.received(msg(), Sentinels.SELF_ID, T0 + 1000)!!
        val p = payload(e)
        assertEquals("message.received", e.json.s("type"))
        assertEquals("hinge", e.json.s("platform"))
        assertEquals("hinge:msg:777", e.json.s("external_message_id"))
        assertEquals(Sentinels.CHANNEL, e.json.s("external_thread_id"))
        assertEquals("chat_message", p.s("kind"))
        assertEquals("linked", p.s("identity"))
        assertEquals("unofficial_api", p.s("source"))
        assertEquals(Sentinels.MATCH_ID, p["sender"]!!.jsonObject.s("external_contact_id"))
        assertEquals(Sentinels.CHANNEL, p.s("external_conversation_id"))
        assertEquals(Sentinels.TEXT, p.s("text"))
        assertEquals("false", p.s("truncated"))
        // Deterministic: a re-sync produces the identical event.
        assertEquals(e.json, mapper.received(msg(), Sentinels.SELF_ID, T0 + 1000)!!.json)
    }

    @Test fun `own and removed messages are skipped`() {
        assertNull(mapper.received(msg(sender = Sentinels.SELF_ID), Sentinels.SELF_ID, T0))
        assertNull(mapper.received(msg(removed = true), Sentinels.SELF_ID, T0))
    }

    @Test fun `likes, comments, admin, files and unknown custom types are never chat`() {
        assertEquals("like_comment", payload(mapper.received(msg(custom = "prompt_comment"), Sentinels.SELF_ID, T0)!!).s("kind"))
        assertEquals("like_comment", payload(mapper.received(msg(custom = "LIKE_WITH_NOTE"), Sentinels.SELF_ID, T0)!!).s("kind"))
        assertEquals("unknown", payload(mapper.received(msg(custom = "voice_note"), Sentinels.SELF_ID, T0)!!).s("kind"))
        assertEquals("system", payload(mapper.received(msg(type = "ADMM"), Sentinels.SELF_ID, T0)!!).s("kind"))
        val file = payload(mapper.received(msg(type = "FILE", text = ""), Sentinels.SELF_ID, T0)!!)
        assertEquals("unknown", file.s("kind"))
        assertEquals("1", file.s("attachment_count"))
        assertEquals("unknown", payload(mapper.received(msg(text = "  "), Sentinels.SELF_ID, T0)!!).s("kind"))
    }

    @Test fun `without the sender's platform id the identity is unlinked and no id is invented from the name`() {
        val p = payload(mapper.received(msg(sender = ""), Sentinels.SELF_ID, T0)!!)
        assertEquals("unlinked", p.s("identity"))
        assertTrue(p["sender"]!!.jsonObject.s("external_contact_id").startsWith("hinge-channel:"))
        assertTrue(!p.toString().contains("\"external_contact_id\":\"Riley\""))
    }

    @Test fun `over-long text is cut to the contract maximum and flagged truncated (a notice, not a message)`() {
        val p = payload(mapper.received(msg(text = "a".repeat(20_005)), Sentinels.SELF_ID, T0)!!)
        assertEquals(20_000, p.s("text").length)
        assertEquals("true", p.s("truncated"))
    }

    @Test fun `a new match is a linked match notice`() {
        val e = mapper.match(Sentinels.MATCH_ID, T0, T0)!!
        assertEquals("hinge:match:${Sentinels.MATCH_ID}", e.json.s("external_message_id"))
        assertEquals(JsonNull, e.json["external_thread_id"])
        assertEquals("match", payload(e).s("kind"))
    }

    @Test fun `envelopes use exactly the contract's 1_3 field names (checked against the contract examples)`() {
        val dir = ContractFiles.contractsDir()
        val example = Json.parseToJsonElement(File(dir, "examples/event.hinge_unofficial_chat_message.json").readText()).jsonObject
        val ours = mapper.received(msg(), Sentinels.SELF_ID, T0)!!.json
        assertEquals(example.keys, ours.keys)
        assertEquals(example["payload"]!!.jsonObject.keys, payload(HingeEnvelope("x", "x", ours)).keys)
        val failed = Json.parseToJsonElement(File(dir, "examples/event.sms_send_failed.json").readText()).jsonObject
        val ourFailed = mapper.sendFailed(OUTBOUND_ID, "send:$OUTBOUND_ID:1", Sentinels.CHANNEL, "timeout", "d", false, T0).json
        assertEquals(failed.keys, ourFailed.keys)
        assertEquals(failed["payload"]!!.jsonObject.keys, ourFailed["payload"]!!.jsonObject.keys)
        val caps = Json.parseToJsonElement(File(dir, "examples/capabilities.hinge_unofficial_api.json").readText()).jsonObject
        val ourCaps = HingeCapabilities.document(T0)
        assertEquals(caps.keys, ourCaps.keys)
        assertEquals(caps["capabilities"]!!.jsonObject.keys, ourCaps["capabilities"]!!.jsonObject.keys)
        for (k in listOf("read", "send")) assertEquals("UNVERIFIED", ourCaps["capabilities"]!!.jsonObject[k]!!.jsonObject.s("status"))
        assertEquals("UNSUPPORTED", ourCaps["capabilities"]!!.jsonObject["swipe"]!!.jsonObject.s("status"))
        val pair = Json.parseToJsonElement(File(dir, "examples/connection.create.hinge_experimental.request.json").readText()).jsonObject
        val ourPair = HingeCapabilities.pairingBody("0192f3a0-0000-4000-8000-0000000000aa", HingeConsentRecord(HingeConsentText.VERSION, T0, true), T0)
        assertEquals(pair.keys, ourPair.keys)
        assertEquals(pair["experimental_consent"]!!.jsonObject.keys, ourPair["experimental_consent"]!!.jsonObject.keys)
        assertEquals("unofficial_api", ourPair.s("transport"))
    }

    @Test fun `every stop reason code is a value the owner's live-test record accepts`() {
        val schema = File(ContractFiles.contractsDir().parentFile, "experiments/dating_platforms/live_test_observation.schema.json")
        val allowed = Json.parseToJsonElement(schema.readText()).jsonObject["properties"]!!.jsonObject["enforcement_signal"]!!
            .jsonObject["enum"]!!.let { it as kotlinx.serialization.json.JsonArray }.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        for (r in HingeStopReason.entries) assertTrue("${r.code} not in the schema", r.code in allowed)
    }

    // --- allowlist -----------------------------------------------------------------------------

    private val identity = HingeClientIdentity(
        "https://prod-api.hinge.invalid", "https://api-app.sendbird.invalid", "wss://ws-app.sendbird.invalid",
        "APP", "4.0.0", "10.0.0", "1", "26.0", "iOS", "Model1,1", "US", "en-US",
    )
    private val allow = HingeAllowlist(identity)

    @Test fun `exactly the reference-documented paths are allowlisted`() {
        fun m(method: String, url: String) = allow.match(method, url.toHttpUrl())
        assertEquals(HingeEndpoint.INSTALL, m("POST", "https://prod-api.hinge.invalid/identity/install"))
        assertEquals(HingeEndpoint.SMS_VERIFY, m("POST", "https://prod-api.hinge.invalid/auth/sms/v2"))
        assertEquals(HingeEndpoint.MATCHES, m("GET", "https://prod-api.hinge.invalid/connection/v2"))
        assertEquals(HingeEndpoint.SB_CHANNELS, m("GET", "https://api-app.sendbird.invalid/v3/users/abc/my_group_channels?limit=5"))
        assertEquals(HingeEndpoint.SB_MESSAGES, m("GET", "https://api-app.sendbird.invalid/v3/group_channels/c1/messages"))
        assertEquals(HingeEndpoint.SB_SOCKET, m("GET", "https://ws-app.sendbird.invalid/?p=iOS"))
        for ((method, url) in listOf(
            "GET" to "https://prod-api.hinge.invalid/rec/v2",
            "POST" to "https://prod-api.hinge.invalid/rate/v2/initiate",
            "GET" to "https://prod-api.hinge.invalid/like/v2",
            "POST" to "https://prod-api.hinge.invalid/message/send",
            "POST" to "https://prod-api.hinge.invalid/user/v3/public",
            "GET" to "https://prod-api.hinge.invalid/connection/subject/abc",
            "GET" to "https://prod-api.hinge.invalid/identity/install", // wrong method
            "POST" to "https://api-app.sendbird.invalid/v3/group_channels", // channel creation
            "GET" to "https://api-app.sendbird.invalid/v3/group_channels/c1/messages/extra",
            "GET" to "https://api-app.sendbird.invalid/v3/users//my_group_channels",
            "GET" to "https://evil.invalid/connection/v2",
            "GET" to "http://prod-api.hinge.invalid/connection/v2", // scheme
        )) assertNull("$method $url", m(method, url))
    }

    @Test fun `a non-allowlisted request is refused before it reaches the network`() {
        val hs = FakeHingeServer()
        try {
            val store = com.wingman.integrations.sms.InMemorySmsStateStore()
            val clock = FakeClock()
            val breaker = HingeCircuitBreaker(store, clock)
            val gate = HingeNetGate(HingeBuildFlags(true, hs.identity()), { true }, breaker, HingeRateLimiter(store, HingeLimits.DEFAULT, clock, FakeSleeper(clock)))
            val client = OkHttpClient.Builder().addInterceptor(HingeGateInterceptor(gate)).build()
            for (path in listOf("/hinge/rec/v2", "/hinge/like/v2", "/hinge/message/send", "/sb/v3/group_channels")) {
                val e = assertThrows(HingeCallRefused::class.java) { client.newCall(Request.Builder().url(hs.server.url(path)).get().build()).execute() }
                assertEquals(HingeCallRefused.NOT_ALLOWLISTED, e.code)
            }
            assertEquals(0, hs.count())
            // An allowlisted one goes through (and is counted).
            hs.on("GET /hinge/connection/v2", json("{}"))
            client.newCall(Request.Builder().url(hs.server.url("/hinge/connection/v2")).get().build()).execute().close()
            assertEquals(1, hs.count())
            assertEquals(1, gate.admittedCalls.get())
        } finally {
            hs.shutdown()
        }
    }

    @Test fun `urls are built only from allowlisted templates with single-segment arguments`() {
        assertEquals("https://api-app.sendbird.invalid/v3/group_channels/a%3Fb/messages?x=1", allow.url(HingeEndpoint.SB_MESSAGES, listOf("a?b"), listOf("x" to "1")).toString())
        assertThrows(IllegalArgumentException::class.java) { allow.url(HingeEndpoint.SB_MESSAGES, listOf("a/b")) }
        assertThrows(IllegalArgumentException::class.java) { allow.url(HingeEndpoint.SB_MESSAGES, listOf("")) }
        assertNotNull(allow.match("GET", allow.url(HingeEndpoint.SB_SOCKET, query = listOf("p" to "iOS"))))
    }
}

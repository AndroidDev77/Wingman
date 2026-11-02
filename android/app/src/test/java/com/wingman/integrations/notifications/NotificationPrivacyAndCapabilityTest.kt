package com.wingman.integrations.notifications

import com.wingman.core.model.ContractFiles
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationPrivacyAndCapabilityTest {

    private val sentinelBody = "SENTINEL-BODY-7f3a"
    private val sentinelName = "Zorblax"
    private val sentinelReply = "SENTINEL-REPLY-91c2"

    private fun sentinelSnapshot(key: String = "k-sentinel", actions: List<SnapshotAction> = listOf(REPLY_ACTION)) = chatSnapshot(
        key = key,
        title = sentinelName,
        text = "$sentinelBody…",
        messages = listOf(SnapshotMessage("$sentinelBody…", 10L, sentinelName, senderKey = "person-$sentinelName")),
        shortcut = "conv-$sentinelName",
        actions = actions + SnapshotAction(3, "Reply to $sentinelName", listOf(SnapshotRemoteInput("r", true))),
    )

    @Test
    fun `no notification or reply text reaches the log`() {
        val h = Harness()
        h.post(sentinelSnapshot())
        h.post(sentinelSnapshot()) // duplicate path
        h.adapter.reply(h.command(conv = "conv-$sentinelName", text = sentinelReply))
        h.adapter.reply(h.command(key = "send:$N_OUTBOUND:2", conv = "conv-$sentinelName", text = sentinelReply)) // missing action path
        h.core.onRemoved("k-sentinel")
        h.core.onAccessRevoked()
        assertTrue(h.log.lines.isNotEmpty())
        for (line in h.log.lines) {
            for (s in listOf(sentinelBody, sentinelName, sentinelReply, "conv-")) {
                assertFalse("log line leaks '$s': $line", line.contains(s, ignoreCase = true))
            }
        }
    }

    @Test
    fun `toString of every private type is redacted`() {
        val h = Harness()
        val out = h.post(sentinelSnapshot()) as PostedOutcome.Processed
        val strings = listOf(
            sentinelSnapshot().toString(),
            sentinelSnapshot().messages.single().toString(),
            sentinelSnapshot().actions.last().toString(),
            out.newEvents.single().toString(),
            out.toString(),
            h.command(conv = "conv-$sentinelName", text = sentinelReply).toString(),
            h.core.liveActions.latestFor(FAKE_PLATFORM, "conv-$sentinelName").toString(),
        )
        for (s in strings) for (p in listOf(sentinelBody, sentinelName, sentinelReply)) assertFalse("$s leaks $p", s.contains(p))
    }

    @Test
    fun `inspector output is redacted structure only`() {
        val snap = sentinelSnapshot()
        val fields = NotificationInspector.describe(source(certs = setOf(GOOD_CERT)), snap)
        val line = NotificationInspector.line(fields)
        for (p in listOf(sentinelBody, sentinelName, "conv-")) assertFalse("inspector leaks $p: $line", line.contains(p, ignoreCase = true))
        assertEquals("len${snap.text!!.length}", fields["text"])
        assertEquals("len${sentinelName.length}", fields["title"])
        assertEquals("1", fields["messages"])
        assertEquals("true", fields["action0.free_form"])
        assertEquals("key_text_reply", fields["action0.result_keys"])
        assertEquals("Reply", fields["action0.title"])
        assertEquals("Reply_to_<w>", fields["action3.title"])
        assertEquals("true", fields["text_ends_with_ellipsis"])
        assertEquals("0", fields["reply_action_candidate"])
        assertEquals(GOOD_CERT, fields["cert_sha256"])
        assertTrue(fields["shortcut_id"]!!.startsWith("present#"))
    }

    @Test
    fun `inspector targets the allowlist plus the candidate packages only`() {
        val t = NotificationInspector.targets(NotificationPluginRegistry(listOf(FakeDatingPlugin())))
        assertEquals(setOf(FAKE_PKG, "com.tinder", "com.bumble.app", "co.hinge.app"), t)
    }

    // ---------------------------------------------------------------- capabilities

    @Test
    fun `capabilities follow observation and opt-in, never verified`() {
        val h = Harness(enableReply = false)
        var c = h.core.capabilities(FAKE_PLATFORM)
        assertEquals(CapabilityStatus.UNVERIFIED, c.read.status)
        assertEquals(CapabilityStatus.UNSUPPORTED, c.send.status) // no RemoteInput observed
        assertEquals(NotificationCapabilityModel.NOTE_NO_REMOTE_INPUT, c.send.note)
        h.post()
        c = h.core.capabilities(FAKE_PLATFORM)
        assertEquals(CapabilityStatus.UNSUPPORTED, c.send.status) // observed, but direct reply is off
        assertEquals(NotificationCapabilityModel.NOTE_REPLY_OFF, c.send.note)
        h.core.settings.setDirectReplyEnabled(FAKE_PLATFORM, true)
        c = h.core.capabilities(FAKE_PLATFORM)
        assertEquals(CapabilityStatus.UNVERIFIED, c.send.status)
        for (s in listOf(c.draft, c.swipe, c.profileRead, c.calendarWrite)) assertEquals(CapabilityStatus.UNSUPPORTED, s.status)
    }

    @Test
    fun `unknown platform and disabled read are unsupported`() {
        val h = Harness(enableRead = false)
        assertEquals(CapabilityStatus.UNSUPPORTED, h.core.capabilities("tinder").read.status)
        assertEquals(CapabilityStatus.UNSUPPORTED, h.core.capabilities(FAKE_PLATFORM).read.status)
    }

    @Test
    fun `capabilities document never claims verified and states profile_read`() {
        val caps = NotificationPlatformCapabilities(
            "tinder",
            read = CapabilityState(CapabilityStatus.VERIFIED, "x"),
            draft = CapabilityState(CapabilityStatus.UNSUPPORTED),
            send = CapabilityState(CapabilityStatus.UNVERIFIED, "y"),
            swipe = CapabilityState(CapabilityStatus.UNSUPPORTED),
            profileRead = CapabilityState(CapabilityStatus.UNSUPPORTED),
            calendarWrite = CapabilityState(CapabilityStatus.UNSUPPORTED),
        )
        val doc = NotificationContractMapping.capabilitiesDocument(caps, 1_793_473_200_000L)
        val c = doc["capabilities"]!!.jsonObject
        assertEquals(setOf("read", "draft", "send", "swipe", "calendar_write", "profile_read"), c.keys)
        assertEquals("UNVERIFIED", c["read"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("UNSUPPORTED", c["profile_read"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("false", c["send"]!!.jsonObject["reports_delivery_receipts"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- contract mapping

    @Test
    fun `every event maps to a v1_3 message_received and only linked complete messages are linked chats`() {
        val h = Harness()
        val linked = (h.post() as PostedOutcome.Processed).newEvents.single()
        val env = NotificationContractMapping.receivedEnvelope(N_SCOPE, linked)
        assertEquals("message.received", env["type"]!!.jsonPrimitive.content)
        assertEquals("conv-abc", env["external_thread_id"]!!.jsonPrimitive.content)
        assertEquals(env, NotificationContractMapping.receivedEnvelope(N_SCOPE, linked)) // deterministic event_id
        val p = env["payload"]!!.jsonObject
        assertEquals("chat_message", p["kind"]!!.jsonPrimitive.content)
        assertEquals("linked", p["identity"]!!.jsonPrimitive.content)
        assertEquals("notification", p["source"]!!.jsonPrimitive.content)
        assertEquals("false", p["content_hidden"]!!.jsonPrimitive.content)
        assertEquals("conv-abc", p["sender"]!!.jsonObject["external_contact_id"]!!.jsonPrimitive.content)
        maybeWriteFixture("event.notification_message_received", env)

        // No stable conversation ID: unlinked, no thread, and the label never becomes an ID.
        val unlinked = (h.post(chatSnapshot(key = "k2", shortcut = null, messages = listOf(SnapshotMessage("hi", 9L, "Kim")))) as PostedOutcome.Processed).newEvents.single()
        val u = NotificationContractMapping.receivedEnvelope(N_SCOPE, unlinked)
        val up = u["payload"]!!.jsonObject
        assertEquals("unlinked", up["identity"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, u["external_thread_id"])
        assertEquals("$FAKE_PLATFORM:unlinked", up["sender"]!!.jsonObject["external_contact_id"]!!.jsonPrimitive.content)
        assertFalse(up.toString().contains("\"external_contact_id\":\"Kim"))
        maybeWriteFixture("event.notification_unlinked", u)

        // Hidden content: content_hidden with empty text (the backend stores a notice).
        val hidden = (h.post(chatSnapshot(key = "k3", title = "Kim", text = "You have a new message", messages = emptyList())) as PostedOutcome.Processed).newEvents.single()
        val hp = NotificationContractMapping.receivedEnvelope(N_SCOPE, hidden)["payload"]!!.jsonObject
        assertEquals("true", hp["content_hidden"]!!.jsonPrimitive.content)
        assertEquals("", hp["text"]!!.jsonPrimitive.content)
        assertEquals("notification", hp["source"]!!.jsonPrimitive.content)
        maybeWriteFixture("event.notification_hidden", NotificationContractMapping.receivedEnvelope(N_SCOPE, hidden))
    }

    @Test
    fun `kinds map to the v1_3 wire values and a linked label without a conversation is unlinked`() {
        assertEquals("chat_message", NotificationContractMapping.wireKind(NotificationEventKind.MESSAGE))
        assertEquals("match", NotificationContractMapping.wireKind(NotificationEventKind.MATCH))
        assertEquals("like_comment", NotificationContractMapping.wireKind(NotificationEventKind.LIKE))
        assertEquals("like_comment", NotificationContractMapping.wireKind(NotificationEventKind.COMMENT))
        assertEquals("expiry", NotificationContractMapping.wireKind(NotificationEventKind.EXPIRY))
        assertEquals("system", NotificationContractMapping.wireKind(NotificationEventKind.SYSTEM))
        assertEquals("unknown", NotificationContractMapping.wireKind(NotificationEventKind.UNKNOWN))
        val h = Harness()
        val linked = (h.post() as PostedOutcome.Processed).newEvents.single()
        val noConv = linked.copy(externalConversationId = null, kind = NotificationEventKind.MATCH)
        val p = NotificationContractMapping.receivedEnvelope(N_SCOPE, noConv)["payload"]!!.jsonObject
        assertEquals("unlinked", p["identity"]!!.jsonPrimitive.content)
        assertEquals("match", p["kind"]!!.jsonPrimitive.content)
        maybeWriteFixture("event.notification_match", NotificationContractMapping.receivedEnvelope(N_SCOPE, noConv))
    }

    @Test
    fun `emitted envelopes use only keys the frozen contract example has`() {
        val h = Harness()
        val e = (h.post() as PostedOutcome.Processed).newEvents.single()
        val env = NotificationContractMapping.receivedEnvelope(N_SCOPE, e)
        val example = File(ContractFiles.examplesDir(), "event.hinge_unofficial_chat_message.json")
        if (example.isFile) {
            val ex = kotlinx.serialization.json.Json.parseToJsonElement(example.readText()).jsonObject
            assertTrue("unexpected keys ${env.keys - ex.keys}", (env.keys - ex.keys).isEmpty())
            val payloadKeys = env["payload"]!!.jsonObject.keys
            val exPayloadKeys = ex["payload"]!!.jsonObject.keys
            assertTrue("unexpected payload keys ${payloadKeys - exPayloadKeys}", (payloadKeys - exPayloadKeys).isEmpty())
        }
        val failed = h.adapter.reply(h.command(conv = "nope")).record
        val failEnv = NotificationContractMapping.outcomeEnvelope(N_SCOPE, failed, h.clock.now)!!
        maybeWriteFixture("event.notification_send_failed", failEnv)
        maybeWriteFixture("capabilities.notification_fakedating", NotificationContractMapping.capabilitiesDocument(h.core.capabilities(FAKE_PLATFORM), h.clock.now))
    }

    private fun maybeWriteFixture(name: String, json: JsonObject) {
        val dir = System.getProperty("wingman.smsFixturesDir")?.takeIf { it.isNotBlank() } ?: return
        // Own subdirectory: SmsContractFixturesTest clears the top-level files of this directory.
        val d = File(dir, "notifications").apply { mkdirs() }
        File(d, "$name.json").writeText(json.toString())
    }
}

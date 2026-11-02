package com.wingman.integrations.hinge

import com.wingman.core.model.OutboxAuthorizationOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HingeSendTest {
    private val rig = HingeRig()
    private val c = rig.c

    @Before fun setUp() {
        rig.loginAndPair()
        rig.syncOneInbound()
    }

    @After fun tearDown() = rig.close()

    private fun failure(e: JsonObject) = e["payload"]!!.jsonObject["failure"]!!.jsonObject
    private fun sendFailed() = rig.backend.ingestedOfType(HingeEventMapper.TYPE_FAILED)

    @Test fun `an approved reply is sent once over the socket and confirmed by the echo`() {
        rig.backend.claimScript += claimOf(instruction())
        val r = c.sendOnce()
        assertEquals(1, r.acknowledged)
        assertEquals(1, rig.sockets.mesgFrames.size)
        val frame = rig.sockets.mesgFrames.single()
        // JSON-built frame: quotes and newlines are escaped, the channel is the thread, newline-terminated.
        assertTrue(frame.endsWith("\n") && frame.contains("\"channel_url\":\"${Sentinels.CHANNEL}\""))
        val ack = rig.backend.ingestedOfType(HingeEventMapper.TYPE_ACK).single()
        assertEquals("hinge:msg:9001", ack["external_message_id"]!!.jsonPrimitive.content)
        assertEquals(OUTBOUND_ID, ack["payload"]!!.jsonObject["outbound_message_id"]!!.jsonPrimitive.content)
        // Waited a 20..90 s jitter before writing.
        assertEquals(HingeLimits.JITTER_MIN to HingeLimits.JITTER_MAX, rig.random.bounds.last())
        assertTrue(rig.sleeper.sleeps.contains(HingeLimits.JITTER_MIN))
        // The claim used this install's id and asked for one instruction.
        assertTrue(rig.backend.wire.any { it.contains("max_instructions=1") || it.contains("maxInstructions=1") })
    }

    @Test fun `a send timeout is never resent and reports nothing (lease_expired, outcome unknown)`() {
        rig.sockets.mode = FakeSockets.Mode.SILENT
        rig.backend.claimScript += claimOf(instruction())
        val r = c.sendOnce()
        assertEquals(1, r.unknown)
        assertEquals(1, rig.sockets.mesgFrames.size)
        assertTrue(rig.backend.ingestedOfType(HingeEventMapper.TYPE_ACK).isEmpty())
        assertTrue(sendFailed().isEmpty())
        // The server re-offers the same lease (e.g. a replayed claim): no second frame, still nothing reported.
        rig.sockets.mode = FakeSockets.Mode.ECHO
        rig.clock.now += 120_000
        rig.backend.claimScript += claimOf(instruction())
        val again = c.sendOnce()
        assertEquals(1, again.replayed)
        assertEquals(1, rig.sockets.mesgFrames.size)
        assertTrue(rig.backend.ingestedOfType(HingeEventMapper.TYPE_ACK).isEmpty())
    }

    @Test fun `a socket closing after the frame was written is also outcome-unknown`() {
        rig.sockets.mode = FakeSockets.Mode.CLOSE
        rig.backend.claimScript += claimOf(instruction())
        assertEquals(1, c.sendOnce().unknown)
        assertTrue(sendFailed().isEmpty())
    }

    @Test fun `a duplicate claim of the same key gives exactly one send`() {
        rig.backend.claimScript += claimOf(instruction(), instruction())
        val r = c.sendOnce()
        assertEquals(1, r.acknowledged)
        assertEquals(1, r.replayed)
        rig.clock.now += 120_000
        rig.backend.claimScript += claimOf(instruction())
        assertEquals(1, c.sendOnce().replayed)
        assertEquals(1, rig.sockets.mesgFrames.size)
        // The replay re-uploads the SAME outcome event (same event id), never a second one.
        assertEquals(1, rig.backend.ingestedOfType(HingeEventMapper.TYPE_ACK).map { it["event_id"] }.toSet().size)
    }

    @Test fun `autopilot is refused on the device even if the server leased it`() {
        rig.backend.claimScript += claimOf(instruction(origin = OutboxAuthorizationOrigin.AUTOPILOT))
        val r = c.sendOnce()
        assertEquals(1, r.failed)
        assertTrue(rig.sockets.mesgFrames.isEmpty())
        val f = failure(sendFailed().single())
        assertEquals("capability_unsupported", f["code"]!!.jsonPrimitive.content)
        assertEquals("origin_not_allowed", f["detail"]!!.jsonPrimitive.content)
        assertEquals("false", f["retryable"]!!.jsonPrimitive.content)
    }

    @Test fun `an approved draft without an approval id is refused too`() {
        rig.backend.claimScript += claimOf(instruction(origin = OutboxAuthorizationOrigin.APPROVED_DRAFT, approvalId = null))
        assertEquals(1, c.sendOnce().failed)
        assertTrue(rig.sockets.mesgFrames.isEmpty())
    }

    @Test fun `the user's own message is allowed`() {
        rig.backend.claimScript += claimOf(instruction(origin = OutboxAuthorizationOrigin.USER_MANUAL))
        assertEquals(1, c.sendOnce().acknowledged)
    }

    @Test fun `no first messages - a thread the match never wrote in is refused`() {
        rig.backend.claimScript += claimOf(instruction(thread = "sendbird_group_channel_new_match"))
        assertEquals(1, c.sendOnce().failed)
        assertTrue(rig.sockets.mesgFrames.isEmpty())
        assertEquals("no_existing_conversation", failure(sendFailed().single())["detail"]!!.jsonPrimitive.content)
    }

    @Test fun `a lease too short for the pre-send delay is refused before anything is written`() {
        rig.backend.claimScript += claimOf(instruction(leaseSeconds = 30))
        assertEquals(1, c.sendOnce().failed)
        assertTrue(rig.sockets.mesgFrames.isEmpty())
        assertEquals("timeout", failure(sendFailed().single())["code"]!!.jsonPrimitive.content)
    }

    @Test fun `a guest refusal drops the chat token and asks the dispatcher for a new attempt`() {
        rig.sockets.mode = FakeSockets.Mode.GUEST
        rig.backend.claimScript += claimOf(instruction())
        assertEquals(1, c.sendOnce().failed)
        val f = failure(sendFailed().single())
        assertEquals("rejected_by_platform", f["code"]!!.jsonPrimitive.content)
        assertEquals("true", f["retryable"]!!.jsonPrimitive.content)
        assertNull(rig.sessions.load()!!.chatToken)
        assertFalse(c.breaker.isStopped())
        assertEquals(1, rig.sockets.mesgFrames.size) // one frame, no in-attempt retry (D-006)
    }

    @Test fun `two non-guest refusals within a day trip the breaker`() {
        rig.sockets.mode = FakeSockets.Mode.REFUSE
        rig.backend.claimScript += claimOf(instruction(key = "send:$OUTBOUND_ID:1"))
        c.sendOnce()
        assertFalse(c.breaker.isStopped())
        rig.clock.now += 120_000
        rig.backend.claimScript += claimOf(instruction(key = "send:$OUTBOUND_ID:2"))
        c.sendOnce()
        assertEquals(HingeStopReason.REPEATED_SEND_REFUSALS, c.breaker.reason())
    }

    @Test fun `ban wording in a refusal stops everything at once`() {
        rig.sockets.mode = FakeSockets.Mode.BAN
        rig.backend.claimScript += claimOf(instruction())
        c.sendOnce()
        assertEquals(HingeStopReason.ACCOUNT_RESTRICTED, c.breaker.reason())
    }

    @Test fun `a frame that could not be written is a known failure`() {
        rig.sockets.mode = FakeSockets.Mode.FAIL_WRITE
        rig.backend.claimScript += claimOf(instruction())
        assertEquals(1, c.sendOnce().failed)
        assertEquals("not_connected", failure(sendFailed().single())["code"]!!.jsonPrimitive.content)
    }

    @Test fun `nothing is claimed while stopped or opted out`() {
        c.breaker.trip(HingeStopReason.PROTOCOL_DRIFT)
        rig.backend.claimScript += claimOf(instruction())
        assertEquals(HingeCallRefused.STOPPED, c.sendOnce().skippedReason)
        assertFalse(rig.backend.calls.contains("claim"))
    }

    @Test fun `the ledger survives a restart - a new executor on the same store never resends`() {
        rig.sockets.mode = FakeSockets.Mode.SILENT
        rig.backend.claimScript += claimOf(instruction())
        c.sendOnce()
        // "Restart": a new connector on the same state store and session store.
        val c2 = HingeConnector(
            rig.flags, rig.store, rig.sessions, rig.backend, HingeLimits.DEFAULT, okhttp3.OkHttpClient(), { rig.sockets },
            rig.clock, rig.sleeper, rig.random, rig.log, echoTimeoutMillis = 100, loginTimeoutMillis = 100,
        )
        rig.sockets.mode = FakeSockets.Mode.ECHO
        rig.clock.now += 120_000
        rig.backend.claimScript += claimOf(instruction())
        assertEquals(1, c2.sendOnce().replayed)
        assertEquals(1, rig.sockets.mesgFrames.size)
    }
}

package com.wingman.integrations.hinge

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HingeCircuitBreakerTest {
    private val rig = HingeRig()
    private val c = rig.c
    private val channels = "GET /sb/v3/users/${Sentinels.SELF_ID}/my_group_channels"
    private val messages = "GET /sb/v3/group_channels/${Sentinels.CHANNEL}/messages"
    private val matches = "GET /hinge/connection/v2"

    @Before fun setUp() = rig.loginAndPair()
    @After fun tearDown() = rig.close()

    private fun nextPass() { rig.clock.now += HingeLimits.FIFTEEN_MINUTES }

    /** After a trip: sync, send and login make NO Hinge/Sendbird call, no socket, no claim. */
    private fun assertEverythingStopped(reason: HingeStopReason) {
        assertEquals(reason, c.breaker.reason())
        assertTrue(rig.stops.contains(reason))
        val requests = rig.hs.count()
        val sockets = rig.sockets.opened.size
        val claims = rig.backend.calls.count { it == "claim" }
        nextPass()
        rig.backend.claimScript += claimOf(instruction())
        assertEquals(HingeCallRefused.STOPPED, c.syncOnce().skippedReason)
        assertEquals(HingeCallRefused.STOPPED, c.sendOnce().skippedReason)
        assertTrue(c.requestCode(Sentinels.PHONE) !is HingeLoginStep.Moved || c.auth.state is HingeLoginState.Blocked)
        assertEquals(requests, rig.hs.count())
        assertEquals(sockets, rig.sockets.opened.size)
        assertEquals(claims, rig.backend.calls.count { it == "claim" })
        // Wingman is told (reason code only) and the connection is reported disconnected.
        val status = rig.backend.ingestedOfType(HingeEventMapper.TYPE_STATUS).last()["payload"]!!.jsonObject
        assertEquals("disconnected", status["status"]!!.jsonPrimitive.content)
        assertEquals(reason.code, status["detail"]!!.jsonPrimitive.content)
    }

    @Test fun `401 from Hinge stops, wipes the session and requires a new login`() {
        rig.hs.on(channels, json(HingeBodies.channels(Sentinels.CHANNEL)))
        rig.hs.on(messages, json(HingeBodies.messages(HingeBodies.message(1))))
        rig.hs.on(matches, json("{}", 401))
        c.syncOnce()
        assertNull(rig.sessions.load())
        assertEverythingStopped(HingeStopReason.AUTH_REJECTED)
    }

    @Test fun `Sendbird 401 gets exactly one chat-token refresh, then stops`() {
        rig.hs.on(channels, json("{\"error\":true}", 401))
        rig.hs.on("POST /hinge/message/authenticate", json(HingeBodies.chatToken()))
        rig.hs.on(channels, json("{\"error\":true}", 401))
        c.syncOnce()
        assertEquals(1, rig.hs.paths().count { it == "POST /hinge/message/authenticate" })
        assertEquals(2, rig.hs.paths().count { it == channels })
        assertEverythingStopped(HingeStopReason.AUTH_REJECTED)
    }

    @Test fun `a Sendbird 401 that one refresh fixes does not stop`() {
        rig.hs.on(channels, json("{\"error\":true}", 401))
        rig.hs.on("POST /hinge/message/authenticate", json(HingeBodies.chatToken()))
        rig.scriptSync(HingeBodies.message(1))
        val r = c.syncOnce()
        assertNull(c.breaker.reason())
        assertEquals(1, r.channelsFetched)
        assertEquals(Sentinels.CHAT_TOKEN_2, rig.sessions.load()!!.chatToken)
    }

    @Test fun `no LOGI after one forced refresh stops as auth_rejected`() {
        rig.sockets.loginKey = null
        rig.hs.on("POST /hinge/message/authenticate", json(HingeBodies.chatToken()))
        c.syncOnce()
        assertEquals(2, rig.sockets.opened.size)
        assertEverythingStopped(HingeStopReason.AUTH_REJECTED)
    }

    @Test fun `426 stops as client_version_retired`() {
        rig.hs.on(channels, json("{}", 426))
        c.syncOnce()
        assertEverythingStopped(HingeStopReason.CLIENT_VERSION_RETIRED)
    }

    @Test fun `429 stops for at least 24 hours, then only a manual re-enable resumes`() {
        rig.hs.on(channels, json("{}", 429).setHeader("Retry-After", "60"))
        c.syncOnce()
        assertEverythingStopped(HingeStopReason.RATE_LIMITED)
        assertFalse(c.reEnable())
        assertFalse(c.status().canReEnable)
        rig.clock.now += 24L * 60 * 60 * 1000
        // Time alone does not resume anything.
        val before = rig.hs.count()
        assertEquals(HingeCallRefused.STOPPED, c.syncOnce().skippedReason)
        assertEquals(before, rig.hs.count())
        assertTrue(c.reEnable())
        rig.scriptSync(HingeBodies.message(1))
        assertNull(c.syncOnce().stopped)
        assertTrue(rig.hs.count() > before)
    }

    @Test fun `a challenge page instead of JSON stops as BLOCKED`() {
        rig.hs.on(channels, MockResponse().setResponseCode(200).setHeader("content-type", "text/html").setBody("<html>verify you are human: captcha</html>"))
        c.syncOnce()
        assertEverythingStopped(HingeStopReason.CHALLENGE_PRESENTED)
    }

    @Test fun `ban wording in an error stops indefinitely`() {
        rig.hs.on(channels, json("{\"channels\":[]}"))
        rig.hs.on(matches, json("{\"message\":\"Your account has been banned\"}", 403))
        c.syncOnce()
        assertEverythingStopped(HingeStopReason.ACCOUNT_RESTRICTED)
        assertTrue(HingeStopReason.ACCOUNT_RESTRICTED.isBlocked)
    }

    @Test fun `ban wording inside a match's chat message is content, not a signal`() {
        rig.scriptSync(HingeBodies.message(1, text = "lol I got banned from karaoke night"))
        c.syncOnce()
        assertNull(c.breaker.reason())
    }

    @Test fun `two unexpected 4xx within an hour stop`() {
        rig.hs.on(channels, json("{}", 404))
        c.syncOnce()
        assertNull(c.breaker.reason())
        nextPass()
        rig.hs.on(channels, json("{}", 404))
        c.syncOnce()
        assertEverythingStopped(HingeStopReason.REPEATED_CLIENT_ERRORS)
    }

    @Test fun `three consecutive 5xx stop`() {
        repeat(3) {
            rig.hs.on(channels, json("{}", 503))
            c.syncOnce()
            nextPass()
        }
        assertEverythingStopped(HingeStopReason.REPEATED_SERVER_ERRORS)
    }

    @Test fun `an undecodable answer stops as protocol_drift`() {
        rig.hs.on(channels, json("{\"something_else\":[]}"))
        c.syncOnce()
        assertEverythingStopped(HingeStopReason.PROTOCOL_DRIFT)
    }

    @Test fun `a network error just ends the pass`() {
        rig.hs.on(channels, MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        c.syncOnce()
        assertNull(c.breaker.reason())
    }

    @Test fun `the stop survives a restart and re-enable is manual`() {
        rig.hs.on(channels, json("{}", 426))
        c.syncOnce()
        val restarted = HingeCircuitBreaker(rig.store, rig.clock)
        assertTrue(restarted.isStopped())
        assertEquals(HingeStopReason.CLIENT_VERSION_RETIRED, restarted.reason())
        assertTrue(c.reEnable())
        assertFalse(restarted.isStopped())
    }

    @Test fun `classifier never reads ban or captcha words in a successful JSON body`() {
        assertEquals(HingeSignal.Ok, HingeResponseClassifier.classify(200, "application/json", "{\"message\":\"banned captcha suspended\"}"))
        assertEquals(HingeSignal.BanWording, HingeResponseClassifier.classify(400, "application/json", "{\"message\":\"account suspended\"}"))
        assertEquals(HingeSignal.Challenge, HingeResponseClassifier.classify(403, "application/json", "{}", headerNames = setOf("cf-mitigated")))
        assertEquals(HingeSignal.RateLimited, HingeResponseClassifier.classify(503, null, null, retryAfter = "30"))
    }
}

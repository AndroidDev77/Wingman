package com.wingman.integrations.hinge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeAuthFlowTest {
    private val rig = HingeRig()
    private val c = rig.c

    @After fun tearDown() = rig.close()

    @Test fun `install, sms code and chat token lead to ChatReady with the tokens persisted first`() {
        rig.scriptLogin()
        assertTrue(c.acceptRisk(true))
        val asked = c.requestCode(Sentinels.PHONE)
        assertEquals(HingeLoginStep.Moved(HingeLoginState.CodeRequested(3)), asked)
        val done = c.submitCode(Sentinels.OTP)
        assertEquals(HingeLoginStep.Moved(HingeLoginState.ChatReady), done)
        assertEquals(listOf("POST /hinge/identity/install", "POST /hinge/auth/sms/v2/initiate", "POST /hinge/auth/sms/v2"), rig.hs.paths())
        val s = rig.sessions.load()!!
        assertEquals(Sentinels.HINGE_TOKEN, s.hingeToken)
        assertEquals(Sentinels.CHAT_TOKEN, s.chatToken)
        assertTrue(s.installRegistered)
        // The code and number went to Hinge, typed by the user, and nowhere else.
        val verify = rig.hs.requests[2].body.readUtf8()
        assertTrue(verify.contains(Sentinels.OTP) && verify.contains(Sentinels.PHONE))
        assertTrue(rig.backend.calls.isEmpty())
        // The reference's headers: no bearer before login.
        assertNull(rig.hs.requests[1].getHeader("authorization"))
        assertEquals("10.0.0", rig.hs.requests[1].getHeader("x-app-version"))
    }

    @Test fun `email code branch on 412 with a case id`() {
        rig.scriptLogin(email = true)
        c.acceptRisk(true)
        c.requestCode(Sentinels.PHONE)
        assertEquals(HingeLoginStep.Moved(HingeLoginState.EmailCodeRequired(3)), c.submitCode(Sentinels.OTP))
        assertEquals(HingeLoginStep.Moved(HingeLoginState.ChatReady), c.submitEmailCode(Sentinels.EMAIL_CODE))
        val validate = rig.hs.requests.first { it.path!!.endsWith("/auth/device/validate") }.body.readUtf8()
        assertTrue(validate.contains(Sentinels.CASE_ID) && validate.contains(Sentinels.EMAIL_CODE))
        // The chat token came from /message/authenticate with the new bearer.
        val auth = rig.hs.requests.last()
        assertTrue(auth.path!!.endsWith("/message/authenticate"))
        assertEquals("Bearer ${Sentinels.HINGE_TOKEN}", auth.getHeader("authorization"))
        assertEquals(Sentinels.CHAT_TOKEN, rig.sessions.load()!!.chatToken)
    }

    @Test fun `wrong code keeps the flow and counts attempts, without tripping the breaker`() {
        rig.hs.on("POST /hinge/identity/install", json("{}"))
        rig.hs.on("POST /hinge/auth/sms/v2/initiate", json("{}"))
        rig.hs.on("POST /hinge/auth/sms/v2", json("{\"error\":\"invalid code\"}", 400))
        rig.hs.on("POST /hinge/auth/sms/v2", json("{\"error\":\"invalid code\"}", 401))
        rig.hs.on("POST /hinge/auth/sms/v2", json(HingeBodies.loginTokens()))
        c.acceptRisk(true)
        c.requestCode(Sentinels.PHONE)
        assertEquals(HingeLoginStep.Moved(HingeLoginState.CodeRequested(2, rejected = true)), c.submitCode("111111"))
        assertEquals(HingeLoginStep.Moved(HingeLoginState.CodeRequested(1, rejected = true)), c.submitCode("222222"))
        assertFalse(c.breaker.isStopped())
        assertEquals(HingeLoginStep.Moved(HingeLoginState.ChatReady), c.submitCode(Sentinels.OTP))
    }

    @Test fun `an expired code three times ends the attempt and a new code must be requested`() {
        rig.hs.on("POST /hinge/identity/install", json("{}"))
        rig.hs.on("POST /hinge/auth/sms/v2/initiate", json("{}"))
        repeat(3) { rig.hs.on("POST /hinge/auth/sms/v2", json("{\"error\":\"code expired\"}", 410)) }
        c.acceptRisk(true)
        c.requestCode(Sentinels.PHONE)
        c.submitCode("123456")
        c.submitCode("123456")
        assertEquals(HingeLoginStep.Moved(HingeLoginState.Idle), c.submitCode("123456"))
        // No code is pending any more: nothing is sent.
        val before = rig.hs.count()
        assertEquals("no_code_requested", (c.submitCode("123456") as HingeLoginStep.Failed).code)
        assertEquals(before, rig.hs.count())
        assertFalse(c.breaker.isStopped())
    }

    @Test fun `a captcha or challenge page stops everything as BLOCKED and nothing is retried`() {
        rig.hs.on("POST /hinge/identity/install", json("{}"))
        rig.hs.on("POST /hinge/auth/sms/v2/initiate", MockResponse403Html())
        c.acceptRisk(true)
        val r = c.requestCode(Sentinels.PHONE)
        assertEquals(HingeLoginStep.Moved(HingeLoginState.Blocked(HingeStopReason.CHALLENGE_PRESENTED)), r)
        assertTrue(HingeStopReason.CHALLENGE_PRESENTED.isBlocked)
        assertEquals(listOf(HingeStopReason.CHALLENGE_PRESENTED), rig.stops)
        val before = rig.hs.count()
        assertEquals(HingeLoginStep.Moved(HingeLoginState.Blocked(HingeStopReason.CHALLENGE_PRESENTED)), c.requestCode(Sentinels.PHONE))
        assertEquals(before, rig.hs.count())
        assertEquals(HingeStopReason.CHALLENGE_PRESENTED, c.status().stop)
    }

    @Test fun `412 without a case id on the code step is a challenge, not an email branch`() {
        rig.hs.on("POST /hinge/identity/install", json("{}"))
        rig.hs.on("POST /hinge/auth/sms/v2/initiate", json("{}"))
        rig.hs.on("POST /hinge/auth/sms/v2", json("{}", 412))
        c.acceptRisk(true)
        c.requestCode(Sentinels.PHONE)
        assertEquals(HingeLoginStep.Moved(HingeLoginState.Blocked(HingeStopReason.CHALLENGE_PRESENTED)), c.submitCode(Sentinels.OTP))
    }

    @Test fun `426 on the first call stops with client_version_retired and never auto-bumps`() {
        rig.hs.on("POST /hinge/identity/install", json("{\"message\":\"Upgrade Required\"}", 426))
        c.acceptRisk(true)
        assertEquals(HingeLoginStep.Moved(HingeLoginState.Blocked(HingeStopReason.CLIENT_VERSION_RETIRED)), c.requestCode(Sentinels.PHONE))
        assertEquals(1, rig.hs.count())
        assertTrue(rig.hs.requests.all { it.getHeader("x-app-version") == "10.0.0" })
    }

    @Test fun `codes are requested at most three times a day, user-initiated only`() {
        repeat(3) { i ->
            if (i == 0) rig.hs.on("POST /hinge/identity/install", json("{}"))
            rig.hs.on("POST /hinge/auth/sms/v2/initiate", json("{}"))
        }
        c.acceptRisk(true)
        repeat(3) { assertTrue(c.requestCode(Sentinels.PHONE) is HingeLoginStep.Moved) }
        val before = rig.hs.count()
        assertEquals(HingeLimitDenial.OTP_REQUESTS.code, (c.requestCode(Sentinels.PHONE) as HingeLoginStep.Failed).code)
        assertEquals(before, rig.hs.count())
    }

    @Test fun `login is refused before consent and the number is validated locally`() {
        assertEquals(HingeCallRefused.NOT_OPTED_IN, (c.requestCode(Sentinels.PHONE) as HingeLoginStep.Failed).code)
        c.acceptRisk(true)
        assertEquals("invalid_phone_number", (c.requestCode("5550109999") as HingeLoginStep.Failed).code)
        assertEquals(0, rig.hs.count())
    }

    @Test fun `consent requires the explicit ban-risk acknowledgement`() {
        val fresh = HingeRig()
        try {
            assertFalse(fresh.c.acceptRisk(false))
            assertFalse(fresh.c.isOptedIn())
            assertTrue(fresh.c.acceptRisk(true))
            val consent = fresh.c.consent()
            assertNotNull(consent)
            assertEquals(HingeConsentText.VERSION, consent!!.textVersion)
            assertTrue(consent.banRiskAcknowledged)
            assertEquals(T0, consent.acceptedAtMillis)
        } finally {
            fresh.close()
        }
    }
}

/** A 403 HTML captcha page, as a bot wall would answer. */
fun MockResponse403Html() = okhttp3.mockwebserver.MockResponse().setResponseCode(403).setHeader("content-type", "text/html")
    .setBody("<html><body>Please complete the captcha to continue</body></html>")

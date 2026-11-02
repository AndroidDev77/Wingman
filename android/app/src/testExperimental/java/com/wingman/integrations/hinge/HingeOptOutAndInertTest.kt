package com.wingman.integrations.hinge

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HingeOptOutAndInertTest {

    @Test fun `opt-out wipes the session at once and makes zero further Hinge or Sendbird calls`() {
        val dir = Files.createTempDirectory("hinge-optout").toFile()
        val cipher = XorCipher()
        val file = File(dir, "session.bin")
        val rig = HingeRig(sessions = EncryptedFileHingeSessionStore(file, cipher))
        try {
            rig.loginAndPair()
            rig.syncOneInbound()
            assertTrue(file.isFile)
            val requests = rig.hs.count()
            val sockets = rig.sockets.opened.size
            val admitted = rig.c.gate.admittedCalls.get()

            val r = rig.c.optOut()

            assertTrue(r.sessionWiped && r.revokedOnServer && !r.revokePending)
            assertFalse(file.exists())
            assertEquals(1, cipher.destroyed)
            assertNull(rig.sessions.load())
            assertEquals(1, rig.cancelled)
            assertTrue(rig.backend.calls.contains("revoke"))
            assertFalse(rig.c.isOptedIn())
            assertNull(rig.c.consent())
            assertFalse(rig.c.status().paired)

            // Everything after: zero calls to Hinge or Sendbird, whatever is invoked.
            rig.clock.now += HingeLimits.FIFTEEN_MINUTES
            rig.backend.claimScript += claimOf(instruction())
            rig.c.syncOnce()
            rig.c.sendOnce()
            rig.c.runPeriodic()
            rig.c.requestCode(Sentinels.PHONE)
            rig.c.submitCode(Sentinels.OTP)
            assertTrue(rig.c.auth.ensureChatToken(force = true) == null)
            assertEquals(requests, rig.hs.count())
            assertEquals(sockets, rig.sockets.opened.size)
            assertEquals(admitted, rig.c.gate.admittedCalls.get())
            assertFalse(rig.backend.calls.subList(rig.backend.calls.indexOf("revoke"), rig.backend.calls.size).contains("claim"))
            assertTrue(rig.sockets.mesgFrames.isEmpty())
        } finally {
            rig.close()
            dir.deleteRecursively()
        }
    }

    @Test fun `admitted-call counter stays flat after opt-out (the live test's L9 counter)`() {
        val rig = HingeRig()
        try {
            rig.loginAndPair()
            val admitted = rig.c.gate.admittedCalls.get()
            rig.c.optOut()
            rig.clock.now += HingeLimits.FIFTEEN_MINUTES
            repeat(3) { rig.c.runPeriodic(); rig.c.syncOnce(); rig.c.sendOnce() }
            assertEquals(admitted, rig.c.gate.admittedCalls.get())
        } finally {
            rig.close()
        }
    }

    @Test fun `revocation offline is persisted and retried with the same key, still without Hinge calls`() {
        val rig = HingeRig()
        try {
            rig.loginAndPair()
            rig.backend.revokeResult = { ApiResult.Failure(ApiError.Network(java.io.IOException("offline"))) }
            val r = rig.c.optOut()
            assertTrue(r.sessionWiped && r.revokePending && !r.revokedOnServer)
            assertNull(rig.sessions.load())
            val firstKey = rig.backend.wire.last()
            rig.backend.revokeResult = { null }
            val before = rig.hs.count()
            assertTrue(rig.c.finishPendingWork())
            assertEquals(firstKey, rig.backend.wire.last { it.startsWith(CONNECTION_ID) })
            assertFalse(rig.c.status().revokePending)
            assertEquals(before, rig.hs.count())
        } finally {
            rig.close()
        }
    }

    @Test fun `queued inbound envelopes are dropped on opt-out, send outcomes are kept`() {
        val q = HingeEnvelopeQueue(com.wingman.integrations.sms.InMemorySmsStateStore())
        val m = HingeEventMapper(USER_ID, CONNECTION_ID)
        q.enqueue(m.match("someone", T0, T0)!!)
        q.enqueue(m.sendFailed(OUTBOUND_ID, "send:$OUTBOUND_ID:1", Sentinels.CHANNEL, "timeout", "x", false, T0))
        q.dropInbound()
        assertEquals(1, q.size())
        assertEquals(HingeEventMapper.TYPE_FAILED, q.pending().single()["type"].toString().trim('"'))
    }

    @Test fun `inert when the experimental flag is off - no consent, no login, no sync, no send, no calls`() {
        val rig = HingeRig(flagsOn = false)
        try {
            rig.scriptLogin()
            assertFalse(rig.c.flags.connectorAvailable)
            assertFalse(rig.c.acceptRisk(true))
            rig.store.put(HingeConnector.K_OPTED_IN, "true") // even a tampered store cannot enable it
            assertEquals(HingeCallRefused.FLAG_OFF, (rig.c.requestCode(Sentinels.PHONE) as HingeLoginStep.Failed).code)
            assertEquals(HingeCallRefused.FLAG_OFF, (rig.c.pair() as HingePairResult.Failed).code)
            rig.store.put(HingeConnector.K_USER, USER_ID)
            rig.store.put(HingeConnector.K_CONNECTION, CONNECTION_ID)
            rig.backend.claimScript += claimOf(instruction())
            assertEquals(HingeCallRefused.FLAG_OFF, rig.c.syncOnce().skippedReason)
            assertEquals(HingeCallRefused.FLAG_OFF, rig.c.sendOnce().skippedReason)
            assertEquals("inactive", rig.c.runPeriodic().skippedReason)
            assertEquals(0, rig.hs.count())
            assertTrue(rig.sockets.opened.isEmpty())
            assertFalse(rig.backend.calls.contains("claim"))
            assertFalse(rig.c.status().available)
        } finally {
            rig.close()
        }
    }

    @Test fun `inert when the client identity is not configured`() {
        val rig = HingeRig(identityOverride = HingeClientIdentity.EMPTY)
        try {
            assertFalse(rig.c.acceptRisk(true))
            rig.store.put(HingeConnector.K_OPTED_IN, "true")
            assertEquals(HingeCallRefused.NOT_CONFIGURED, (rig.c.requestCode(Sentinels.PHONE) as HingeLoginStep.Failed).code)
            assertEquals(0, rig.hs.count())
        } finally {
            rig.close()
        }
    }

    @Test fun `build flags default OFF when BuildConfig lacks the fields`() {
        assertEquals(HingeBuildFlags.OFF, HingeBuildFlags.fromBuildConfig("com.wingman.does.not.Exist"))
        assertEquals(HingeBuildFlags.OFF, HingeBuildFlags.fromBuildConfig(FlagOff::class.java.name))
        val on = HingeBuildFlags.fromBuildConfig(FlagOnIncomplete::class.java.name)
        assertTrue(on.experimentalEnabled)
        assertFalse(on.connectorAvailable)
        val full = HingeBuildFlags.fromBuildConfig(FlagOnComplete::class.java.name)
        assertTrue(full.connectorAvailable)
        assertEquals("10.0.0", full.identity.appVersion)
        assertEquals("TestAgent/1", full.identity.hingeUserAgent())
        // The user-agent is part of the client identity (integrator, D-026): without it, inert.
        assertFalse(HingeBuildFlags.fromBuildConfig(FlagOnWithoutUserAgent::class.java.name).connectorAvailable)
    }
}

/** Stand-ins for `com.wingman.BuildConfig` variants (fields read reflectively). */
object FlagOff {
    @JvmField val WINGMAN_EXPERIMENTAL = false
}

object FlagOnIncomplete {
    @JvmField val WINGMAN_EXPERIMENTAL = true
}

object FlagOnComplete {
    @JvmField val WINGMAN_EXPERIMENTAL = true
    @JvmField val HINGE_BASE_URL = "https://hinge.invalid"
    @JvmField val HINGE_SENDBIRD_API_URL = "https://api-test.invalid"
    @JvmField val HINGE_SENDBIRD_WS_URL = "wss://ws-test.invalid"
    @JvmField val HINGE_SENDBIRD_APP_ID = "TEST"
    @JvmField val HINGE_SENDBIRD_SDK_VERSION = "4.0.0"
    @JvmField val HINGE_APP_VERSION = "10.0.0"
    @JvmField val HINGE_BUILD_NUMBER = "1"
    @JvmField val HINGE_OS_VERSION = "26.0"
    @JvmField val HINGE_DEVICE_PLATFORM = "iOS"
    @JvmField val HINGE_DEVICE_MODEL_CODE = "Test1,1"
    @JvmField val HINGE_DEVICE_REGION = "US"
    @JvmField val HINGE_ACCEPT_LANGUAGE = "en-US"
    @JvmField val HINGE_USER_AGENT = "TestAgent/1"
}

/** Every identity value but the user-agent: still incomplete, so still inert. */
object FlagOnWithoutUserAgent {
    @JvmField val WINGMAN_EXPERIMENTAL = true
    @JvmField val HINGE_BASE_URL = "https://hinge.invalid"
    @JvmField val HINGE_SENDBIRD_API_URL = "https://api-test.invalid"
    @JvmField val HINGE_SENDBIRD_WS_URL = "wss://ws-test.invalid"
    @JvmField val HINGE_SENDBIRD_APP_ID = "TEST"
    @JvmField val HINGE_SENDBIRD_SDK_VERSION = "4.0.0"
    @JvmField val HINGE_APP_VERSION = "10.0.0"
    @JvmField val HINGE_BUILD_NUMBER = "1"
    @JvmField val HINGE_OS_VERSION = "26.0"
    @JvmField val HINGE_DEVICE_PLATFORM = "iOS"
    @JvmField val HINGE_DEVICE_MODEL_CODE = "Test1,1"
    @JvmField val HINGE_DEVICE_REGION = "US"
    @JvmField val HINGE_ACCEPT_LANGUAGE = "en-US"
}

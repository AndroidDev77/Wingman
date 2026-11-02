package com.wingman.integrations.hinge

import com.wingman.integrations.sms.InMemorySmsStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeRateLimitTest {
    private val clock = FakeClock()
    private val sleeper = FakeSleeper(clock)
    private val store = InMemorySmsStateStore()

    @Test fun `the defaults are the documented ceilings`() {
        val d = HingeLimits.DEFAULT
        assertEquals(15L * 60_000, d.minSyncIntervalMillis)
        assertEquals(600, d.maxCallsPerDay)
        assertEquals(20, d.maxSendsPerDay)
        assertEquals(20_000L, d.sendJitterMinMillis)
        assertEquals(90_000L, d.sendJitterMaxMillis)
    }

    @Test fun `limits can only be made stricter`() {
        // Stricter is fine.
        HingeLimits(minSyncIntervalMillis = 60 * 60_000, maxCallsPerDay = 100, maxSendsPerDay = 5, sendJitterMinMillis = 30_000, sendJitterMaxMillis = 120_000)
        // Looser is rejected.
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(minSyncIntervalMillis = 60_000) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(maxCallsPerDay = 601) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(maxCallsPerHour = 500) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(minCallSpacingMillis = 100) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(maxSendsPerDay = 21) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(maxSendsPerConversationPerDay = 6) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(minSendSpacingMillis = 1_000) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(sendJitterMinMillis = 1_000) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(sendJitterMaxMillis = 30_000) }
        assertThrows(IllegalArgumentException::class.java) { HingeLimits(maxOtpRequestsPerDay = 4) }
    }

    @Test fun `600 calls a day, then refused without waiting, and the count survives a restart`() {
        val l = HingeRateLimiter(store, HingeLimits.DEFAULT, clock, sleeper)
        clock.now = (T0 / DAY) * DAY // start of a UTC day
        repeat(600) {
            if (it % 120 == 0 && it > 0) clock.now += HOUR // stay under the hourly cap
            assertNull(l.admitCall())
        }
        val restarted = HingeRateLimiter(store, HingeLimits.DEFAULT, clock, sleeper)
        assertEquals(HingeLimitDenial.DAILY_CALLS, restarted.admitCall())
        clock.now += DAY
        assertNull(restarted.admitCall())
    }

    @Test fun `120 calls an hour and 2 s spacing (waited, not failed)`() {
        val l = HingeRateLimiter(store, HingeLimits.DEFAULT, clock, sleeper)
        clock.now = (T0 / HOUR) * HOUR
        assertNull(l.admitCall())
        assertNull(l.admitCall())
        assertEquals(listOf(2_000L), sleeper.sleeps)
        repeat(118) { assertNull(l.admitCall()) }
        assertEquals(HingeLimitDenial.HOURLY_CALLS, l.admitCall())
    }

    @Test fun `sends - 20 a day, 5 per conversation, 60 s apart`() {
        val l = HingeRateLimiter(store, HingeLimits.DEFAULT, clock, sleeper)
        clock.now = (T0 / DAY) * DAY
        repeat(5) {
            assertNull(l.sendDenial("a"))
            l.recordSend("a")
            assertEquals(HingeLimitDenial.SEND_SPACING, l.sendDenial("b"))
            clock.now += 60_000
        }
        assertEquals(HingeLimitDenial.CONVERSATION_SENDS, l.sendDenial("a"))
        for (conv in listOf("b", "c", "d")) repeat(5) {
            assertNull(l.sendDenial(conv))
            l.recordSend(conv)
            clock.now += 60_000
        }
        assertEquals(HingeLimitDenial.DAILY_SENDS, l.sendDenial("e"))
        assertEquals(20, l.sendsToday())
    }

    @Test fun `sync at most every 15 minutes`() {
        val l = HingeRateLimiter(store, HingeLimits.DEFAULT, clock, sleeper)
        assertNull(l.syncDenial())
        l.recordSync()
        clock.now += 14 * 60_000
        assertEquals(HingeLimitDenial.SYNC_TOO_SOON, l.syncDenial())
        clock.now += 60_000
        assertNull(l.syncDenial())
    }

    @Test fun `a sync inside 15 minutes makes no network call`() {
        val rig = HingeRig()
        try {
            rig.loginAndPair()
            rig.syncOneInbound()
            rig.clock.now -= 60_000 // only 14 minutes after the last sync
            val before = rig.hs.count()
            assertEquals(HingeLimitDenial.SYNC_TOO_SOON.code, rig.c.syncOnce().skippedReason)
            assertEquals(before, rig.hs.count())
        } finally {
            rig.close()
        }
    }

    @Test fun `the daily call cap refuses at the gate before the network`() {
        val rig = HingeRig(limits = HingeLimits(maxCallsPerDay = 4))
        try {
            rig.scriptLogin()
            rig.c.acceptRisk(true)
            rig.c.requestCode(Sentinels.PHONE) // install + initiate = 2 calls
            rig.c.submitCode(Sentinels.OTP) // verify = 3
            rig.c.pair()
            rig.scriptSync(HingeBodies.message(1))
            val r = rig.c.syncOnce() // socket open = 4; the channel list is refused locally
            assertEquals(HingeCallRefused.RATE_LIMITED, r.skippedReason)
            assertEquals(3, rig.hs.count())
            assertEquals(1, rig.sockets.opened.size)
            assertTrue(!rig.c.breaker.isStopped())
        } finally {
            rig.close()
        }
    }

    @Test fun `20 approved sends a day, end to end`() {
        val rig = HingeRig()
        try {
            rig.loginAndPair()
            rig.syncOneInbound()
            // Make 20 other conversations known so the per-conversation cap does not hit first.
            repeat(21) { rig.store.put(HingeOutboxExecutor.knownConversationKey("chan_$it"), "1") }
            repeat(21) { n ->
                rig.backend.claimScript += claimOf(instruction(key = "send:$OUTBOUND_ID:${n + 1}", thread = "chan_$n"))
                rig.c.sendOnce()
                rig.clock.now += 61_000
            }
            assertEquals(20, rig.sockets.mesgFrames.size)
        } finally {
            rig.close()
        }
    }

    companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}

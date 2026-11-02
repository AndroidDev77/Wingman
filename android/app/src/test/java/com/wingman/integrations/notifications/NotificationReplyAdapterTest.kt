package com.wingman.integrations.notifications

import com.wingman.core.model.OutboxAuthorization
import com.wingman.core.model.OutboxAuthorizationOrigin
import com.wingman.core.model.OutboxClaimResult
import com.wingman.core.model.OutboxInstruction
import com.wingman.core.model.OutboxInstructionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotificationReplyAdapterTest {

    @Test
    fun `authorized reply fires once and records submitted, never sent`() {
        val h = Harness()
        h.post()
        val out = h.adapter.reply(h.command())
        assertTrue(out is ReplyOutcome.Submitted)
        assertEquals(ReplyState.SUBMITTED, out.record.state)
        assertEquals(listOf("Yes! 8pm?"), h.handles.single().fired)
        assertEquals(1, h.adapter.fireAttempts("send:$N_OUTBOUND:1"))
        // Single-use: the action is gone after firing.
        assertEquals(0, h.core.liveActions.size())
        // No contract envelope claims `sent` for a submitted reply.
        assertNull(NotificationContractMapping.outcomeEnvelope(N_SCOPE, out.record, h.clock.now))
    }

    @Test
    fun `missing reply action is a typed failure with no send claim`() {
        val h = Harness()
        h.post(chatSnapshot(actions = listOf(MARK_READ_ACTION)))
        val out = h.adapter.reply(h.command())
        assertEquals(ReplyFailureReason.ACTION_MISSING, (out as ReplyOutcome.Failed).reason)
        assertEquals(0, h.adapter.fireAttempts("send:$N_OUTBOUND:1"))
        assertFalse(out.record.actionFired)
        val env = NotificationContractMapping.outcomeEnvelope(N_SCOPE, out.record, h.clock.now)!!
        assertEquals("\"message.send_failed\"", env["type"].toString())
        assertTrue(env.toString().contains("capability_unsupported"))
    }

    @Test
    fun `reply to another conversation has no action`() {
        val h = Harness()
        h.post()
        val out = h.adapter.reply(h.command(conv = "conv-other"))
        assertEquals(ReplyFailureReason.ACTION_MISSING, (out as ReplyOutcome.Failed).reason)
        assertTrue(h.handles.single().fired.isEmpty())
    }

    @Test
    fun `stale action is not used`() {
        val h = Harness()
        h.post()
        h.clock.now += 7L * 60 * 60 * 1000
        val out = h.adapter.reply(h.command(deadline = h.clock.now + 60_000))
        assertEquals(ReplyFailureReason.ACTION_STALE, (out as ReplyOutcome.Failed).reason)
        assertTrue(h.handles.single().fired.isEmpty())
    }

    @Test
    fun `canceled pending intent is a failure, not a send`() {
        val h = Harness()
        h.post()
        h.handles.single().result = FireResult.Canceled
        val out = h.adapter.reply(h.command())
        assertEquals(ReplyFailureReason.ACTION_CANCELED, (out as ReplyOutcome.Failed).reason)
        assertTrue(out.record.actionFired)
        assertTrue(NotificationContractMapping.outcomeEnvelope(N_SCOPE, out.record, h.clock.now).toString().contains("rejected_by_platform"))
    }

    @Test
    fun `direct reply off for the platform refuses`() {
        val h = Harness(enableReply = false)
        h.post()
        val out = h.adapter.reply(h.command())
        assertEquals(ReplyFailureReason.DIRECT_REPLY_DISABLED, (out as ReplyOutcome.Failed).reason)
        assertTrue(h.handles.single().fired.isEmpty())
    }

    @Test
    fun `revoked access refuses and nothing fires`() {
        val h = Harness()
        h.post()
        val handle = h.handles.single()
        h.core.onAccessRevoked()
        val out = h.adapter.reply(h.command())
        assertEquals(ReplyFailureReason.ACCESS_REVOKED, (out as ReplyOutcome.Failed).reason)
        assertTrue(handle.fired.isEmpty())
        assertTrue(NotificationContractMapping.outcomeEnvelope(N_SCOPE, out.record, h.clock.now).toString().contains("permission_denied"))
    }

    @Test
    fun `lease deadline passed refuses with a retryable timeout`() {
        val h = Harness()
        h.post()
        val out = h.adapter.reply(h.command(deadline = h.clock.now))
        assertEquals(ReplyFailureReason.LEASE_DEADLINE_PASSED, (out as ReplyOutcome.Failed).reason)
        assertTrue(h.handles.single().fired.isEmpty())
        val env = NotificationContractMapping.outcomeEnvelope(N_SCOPE, out.record, h.clock.now).toString()
        assertTrue(env.contains("\"timeout\"") && env.contains("\"retryable\":true"))
    }

    @Test
    fun `same key twice fires once, different text is a key conflict`() {
        val h = Harness()
        h.post()
        h.adapter.reply(h.command())
        h.post(chatSnapshot(post = 2L, messages = listOf(SnapshotMessage("new", 3L, "Sam"))))
        val again = h.adapter.reply(h.command())
        assertTrue(again is ReplyOutcome.Submitted && again.replayed)
        assertTrue(h.adapter.reply(h.command(text = "different")) is ReplyOutcome.KeyConflict)
        assertEquals(1, h.handles.sumOf { it.fired.size })
    }

    @Test
    fun `refused key stays used even after the action appears`() {
        val h = Harness()
        val first = h.adapter.reply(h.command()) // no notification yet
        assertEquals(ReplyFailureReason.ACTION_MISSING, (first as ReplyOutcome.Failed).reason)
        h.post()
        val again = h.adapter.reply(h.command())
        assertTrue(again is ReplyOutcome.Failed && again.replayed)
        assertTrue(h.handles.single().fired.isEmpty())
    }

    @Test
    fun `one submit per key across a restart`() {
        val store = InMemoryNotificationStateStore()
        val h = Harness(store = store)
        h.post()
        h.adapter.reply(h.command())
        // New process: same state file, the listener reconnects and replays active notifications.
        val h2 = Harness(store = store, grantAccess = false, enableRead = false, enableReply = false)
        h2.core.onAccessGranted()
        h2.adapter.recoverAfterRestart()
        h2.post(chatSnapshot(post = 5L, messages = listOf(SnapshotMessage("still there?", 6L, "Sam"))))
        val out = h2.adapter.reply(h2.command())
        assertTrue(out is ReplyOutcome.Submitted && out.replayed)
        assertTrue(h2.handles.all { it.fired.isEmpty() })
        assertEquals(1, h2.adapter.fireAttempts("send:$N_OUTBOUND:1"))
    }

    @Test
    fun `process death between claim and result is outcome unknown and never refired`() {
        val store = InMemoryNotificationStateStore()
        val h = Harness(store = store)
        h.post()
        h.handles.single().result = FireResult.Fired
        // Simulate death inside fire(): the ledger has CLAIMED + actionFired, no result.
        val dying = object : ReplyActionHandle {
            override fun fire(text: String): FireResult = throw ProcessDeath()
        }
        h.core.liveActions.put(h.core.liveActions.latestFor(FAKE_PLATFORM, "conv-abc")!!.let {
            LiveReplyAction(it.notificationKey, it.postTimeMillis, it.platform, it.externalConversationId, it.actionIndex, it.remoteInputKeys, dying, it.registeredAtMillis)
        })
        runCatching { h.adapter.reply(h.command()) }
        // FIRE_ERROR is recorded in-process; model a hard kill by rewriting the record to CLAIMED.
        val rec = h.adapter.record("send:$N_OUTBOUND:1")!!
        store.put(NotificationReplyAdapter.PREFIX + rec.idempotencyKey, kotlinx.serialization.json.Json.encodeToString(ReplyRecord.serializer(), rec.copy(state = ReplyState.CLAIMED, failureReason = null)))

        val h2 = Harness(store = store, grantAccess = false, enableRead = false, enableReply = false)
        assertEquals(1, h2.adapter.recoverAfterRestart())
        h2.core.onAccessGranted()
        h2.post()
        val out = h2.adapter.reply(h2.command())
        assertEquals(ReplyFailureReason.OUTCOME_UNKNOWN, (out as ReplyOutcome.Failed).reason)
        assertTrue(out.replayed)
        assertTrue(h2.handles.all { it.fired.isEmpty() })
        assertTrue(NotificationContractMapping.outcomeEnvelope(N_SCOPE, out.record, 0L).toString().contains("\"retryable\":false"))
    }

    private class ProcessDeath : RuntimeException()

    @Test
    fun `claimed instruction is the only way in and other connections are ignored`() {
        val h = Harness()
        h.post()
        val executor = NotificationClaimExecutor(N_SCOPE, FAKE_PLATFORM, h.adapter)
        val now = Instant.ofEpochMilli(h.clock.now)
        val mine = instruction(N_CONNECTION, "send:$N_OUTBOUND:1", now)
        val foreign = instruction("0192f3a0-0000-7000-8000-0000000000ff", "send:$N_OUTBOUND:9", now)
        val report = executor.execute(OutboxClaimResult(listOf(mine, foreign), now), anchorMillis = h.clock.now)
        assertEquals(NotificationClaimReport(claimed = 2, submitted = 1, failed = 0, replayed = 0, ignored = 1), report)
        assertEquals("approved_draft/approval-9", h.adapter.record("send:$N_OUTBOUND:1")!!.authorizedBy)
        // Replay of the same claim answer (lost response): nothing fires again.
        val replay = executor.execute(OutboxClaimResult(listOf(mine), now), anchorMillis = h.clock.now)
        assertEquals(1, replay.replayed)
        assertEquals(1, h.handles.sumOf { it.fired.size })
    }

    @Test
    fun `expired lease from a claim is not fired`() {
        val h = Harness()
        h.post()
        val now = Instant.ofEpochMilli(h.clock.now)
        val i = instruction(N_CONNECTION, "send:$N_OUTBOUND:1", now, leaseSeconds = 10) // 10 s lease < 15 s margin
        val report = NotificationClaimExecutor(N_SCOPE, FAKE_PLATFORM, h.adapter).execute(OutboxClaimResult(listOf(i), now), h.clock.now)
        assertEquals(1, report.failed)
        assertNotNull(h.adapter.record("send:$N_OUTBOUND:1"))
        assertTrue(h.handles.single().fired.isEmpty())
    }

    private fun instruction(connection: String, key: String, now: Instant, leaseSeconds: Long = 120) = OutboxInstruction(
        id = N_INSTRUCTION, connectionId = connection, outboundMessageId = N_OUTBOUND, idempotencyKey = key, attempt = 1,
        state = OutboxInstructionState.LEASED, createdAt = now, leasedAt = now, leaseExpiresAt = now.plusSeconds(leaseSeconds),
        conversationId = "0192f3a0-0000-7000-8000-000000000300", externalThreadId = "conv-abc", text = "Yes! 8pm?",
        authorization = OutboxAuthorization(OutboxAuthorizationOrigin.APPROVED_DRAFT, "approval-9"),
    )
}

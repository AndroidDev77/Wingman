package com.wingman.features.discovery

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.ProfileQueueFilter
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockProfileFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.Capability
import com.wingman.core.model.CapabilitySet
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionSettingsUpdate
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.Page
import com.wingman.core.model.Profile
import com.wingman.core.model.ProfileAction
import com.wingman.core.model.ProfileActionExecution
import com.wingman.core.model.ProfileDecision
import com.wingman.core.model.ProfileDecisionRequest
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ProfileSource
import com.wingman.core.model.Verification
import com.wingman.features.conversation.settle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** WING-031 Discovery review workflow over MockWingmanApi (contract 1.3.1 semantics). Fictional profiles only. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryStateTest {
    private val riley = MockProfileFixtures.PROFILE_RILEY
    private val sam = MockProfileFixtures.PROFILE_SAM
    private val jo = MockProfileFixtures.PROFILE_JO
    private val alex = MockProfileFixtures.PROFILE_ALEX
    private val install = "0192f3a0-0000-4000-8000-0000000031aa"

    /** Records every decision / execution call with its key; can hold a call open or fail it once. */
    class Api(val mock: MockWingmanApi = MockWingmanApi()) : WingmanApi by mock {
        val decisions = mutableListOf<Triple<String, ProfileDecision, String>>()
        val executions = mutableListOf<Pair<String, String>>()
        val queueFilters = mutableListOf<ProfileQueueFilter>()
        var gate: CompletableDeferred<Unit>? = null
        var failNextDecision: ApiResult.Failure? = null

        override suspend fun recordProfileDecision(profileId: String, body: ProfileDecisionRequest, key: IdempotencyKey): ApiResult<ProfileAction> {
            decisions += Triple(profileId, body.decision, key.value)
            gate?.await()
            failNextDecision?.let { failNextDecision = null; return it }
            return mock.recordProfileDecision(profileId, body, key)
        }

        override suspend fun requestProfileActionExecution(profileActionId: String, key: IdempotencyKey): ApiResult<ProfileAction> {
            executions += profileActionId to key.value
            return mock.requestProfileActionExecution(profileActionId, key)
        }

        override suspend fun listProfileReviewQueue(page: PageRequest, filter: ProfileQueueFilter): ApiResult<Page<Profile>> {
            queueFilters += filter
            return mock.listProfileReviewQueue(page, filter)
        }
    }

    private fun TestScope.holder(api: Api = Api()) = DiscoveryState(backgroundScope, api).also { it.load(); settle() }

    private fun DiscoveryState.card(id: String) = state.value.cards.first { it.id == id }

    private suspend fun pairHinge(api: Api, swipe: Verification = Verification.UNSUPPORTED): Connection {
        val caps = ConnectorCapabilities(
            "connector_capabilities.v1", "hinge", "0.1.0",
            CapabilitySet(Capability(Verification.UNVERIFIED), Capability(Verification.UNSUPPORTED), Capability(Verification.UNSUPPORTED), Capability(swipe), Capability(Verification.UNSUPPORTED)),
            Instant.parse("2026-11-01T18:00:00Z"),
        )
        return api.mock.createConnection(ConnectionCreateRequest("hinge", "Hinge", install, caps, ConnectionTransport.NOTIFICATION), IdempotencyKey("pair-hinge-0001")).getOrNull()!!
    }

    /** Every label a user can read on the screen for [s]. */
    private fun allText(s: DiscoveryState): List<String> =
        s.state.value.cards.flatMap { listOfNotNull(it.statusLabel, it.execute.label, it.execute.explanation) } + listOfNotNull(s.state.value.message)

    private val claimsCompletion = Regex("""\b(liked|passed|swiped|superliked|matched)\b.*\bon (Tinder|Bumble|Hinge)\b|\b(Tinder|Bumble|Hinge)\b.*\b(liked|swiped)\b""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------ queue

    @Test fun queueShowsRankedCardsWithScoreReasonsCitationsUnknownsAndStaleBadge() = runTest {
        val s = holder()
        val ui = s.state.value
        assertFalse(ui.loading)
        // Excluded profile hidden by default; fresh before stale; scored before unscored.
        assertEquals(listOf(riley, jo, alex), ui.cards.map { it.id })
        val r = s.card(riley)
        assertEquals("80 / 100", r.scoreLabel)
        assertEquals("Confidence: medium", r.confidenceLabel)
        assertEquals("Hinge", r.platformLabel)
        assertTrue(r.reasons.any { it.cites == listOf("looking for") })
        assertTrue(r.reasons.any { it.cites == listOf("smoking") })
        assertFalse(r.stale || r.excluded)
        val j = s.card(jo)
        assertEquals(DiscoveryCopy.NOT_SCORED, j.scoreLabel)
        assertEquals(listOf("What they're looking for", "Smoking"), j.unknowns)
        assertEquals("Confidence: low", j.confidenceLabel)
        assertTrue(s.card(alex).stale)
        assertNull(r.decision)
        assertNull(r.statusLabel)
        assertFalse(r.toString().contains("karaoke"))
    }

    @Test fun showExcludedReloadsWithTheFlagAndShowsTheDealbreaker() = runTest {
        val api = Api()
        val s = holder(api)
        s.setShowExcluded(true)
        settle()
        assertEquals(true, api.queueFilters.last().includeExcluded)
        val excluded = s.card(sam)
        assertTrue(excluded.excluded)
        assertEquals(listOf("smoking"), excluded.dealbreakers)
        assertEquals("0 / 100", excluded.scoreLabel)
        assertEquals(sam, s.state.value.cards.last().id)
        assertTrue(excluded.reasons.any { it.text.contains("dealbreaker") && it.cites == listOf("smoking") })
        s.setShowExcluded(false)
        settle()
        assertFalse(s.state.value.cards.any { it.id == sam })
    }

    // ------------------------------------------------------------------ decisions

    @Test fun doubleTapRecordsOneDecisionWithOneKey() = runTest {
        val api = Api()
        api.gate = CompletableDeferred()
        val s = holder(api)
        s.decide(riley, ProfileDecision.LIKE)
        runCurrent()
        assertTrue(s.card(riley).busy)
        s.decide(riley, ProfileDecision.LIKE) // second tap while the first runs
        s.decide(riley, ProfileDecision.PASS) // or a different button: also dropped
        runCurrent()
        api.gate!!.complete(Unit)
        settle()
        assertEquals(1, api.decisions.size)
        assertEquals(1, api.mock.profileActions().size)
        val card = s.card(riley)
        assertEquals(ProfileDecision.LIKE, card.decision)
        assertFalse(card.busy)
        assertEquals("Decision recorded (Like) · Not executable on Hinge", card.statusLabel)
        assertEquals(DiscoveryCopy.DECISION_SAVED, s.state.value.message)
    }

    @Test fun aRetryAfterANetworkErrorReusesTheKeyAndTheServerReplays() = runTest {
        val api = Api()
        val s = holder(api)
        api.failNextDecision = ApiResult.Failure(ApiError.Network(IOException("offline")))
        s.decide(jo, ProfileDecision.PASS)
        settle()
        assertNull(s.card(jo).decision)
        assertTrue(s.state.value.message!!.startsWith("Couldn't record that"))
        s.decide(jo, ProfileDecision.PASS)
        settle()
        assertEquals(2, api.decisions.size)
        assertEquals("same key on retry", api.decisions[0].third, api.decisions[1].third)
        assertEquals(ProfileDecision.PASS, s.card(jo).decision)
        // A new tap after success is a new action with a new key.
        s.decide(jo, ProfileDecision.SKIP)
        settle()
        assertNotEquals(api.decisions[1].third, api.decisions[2].third)
        // The mock stored the lost-response key once: replaying it does not add an action.
        assertEquals(2, api.mock.profileActions().size)
    }

    @Test fun rateLimitedDecisionSaysSoAndKeepsTheKey() = runTest {
        val api = Api()
        val s = holder(api)
        api.failNextDecision = MockWingmanApi.problem(429, com.wingman.core.model.ErrorCode.RATE_LIMITED, "slow") as ApiResult.Failure
        s.decide(riley, ProfileDecision.LIKE)
        settle()
        assertTrue(s.state.value.message!!.contains("Wait a moment"))
        s.decide(riley, ProfileDecision.LIKE)
        settle()
        assertEquals(api.decisions[0].third, api.decisions[1].third)
    }

    @Test fun skipKeepsTheProfileInTheQueue() = runTest {
        val api = Api()
        val s = holder(api)
        s.decide(jo, ProfileDecision.SKIP)
        settle()
        s.load()
        settle()
        assertTrue(api.mock.listProfileReviewQueue().getOrNull()!!.items.any { it.id == jo })
        assertEquals("Decision recorded (Skip) · Back in the queue later", s.card(jo).statusLabel)
        assertEquals("Skip is never sent to Tinder.", s.card(jo).execute.explanation)
    }

    // ------------------------------------------------------------------ the execute control

    @Test fun executeControlIsDisabledAndExplainedWithoutAVerifiedSwipe() = runTest {
        val api = Api()
        val s = holder(api)
        val before = s.card(riley).execute
        assertEquals("Execute on Hinge", before.label)
        assertFalse(before.enabled)
        assertTrue(before.explanation.startsWith("Record a decision first"))
        s.decide(riley, ProfileDecision.LIKE)
        settle()
        val noConnection = s.card(riley).execute
        assertFalse(noConnection.enabled)
        assertTrue(noConnection.explanation.contains("No Hinge connection"))
        // Tapping it anyway does nothing: no request.
        s.execute(riley)
        settle()
        assertTrue(api.executions.isEmpty())
        // With a paired Hinge connection whose swipe is UNSUPPORTED (every real one today).
        pairHinge(api)
        s.load()
        settle()
        s.decide(riley, ProfileDecision.LIKE)
        settle()
        val unsupported = s.card(riley).execute
        assertFalse(unsupported.enabled)
        assertTrue(unsupported.explanation, unsupported.explanation.contains("Wingman can't swipe on Hinge"))
        assertTrue(unsupported.explanation.contains("UNSUPPORTED"))
        s.execute(riley)
        settle()
        assertTrue(api.executions.isEmpty())
        // Demo profiles and stale ones are never executable either.
        assertTrue(s.card(alex).execute.explanation.startsWith("Record a decision first"))
    }

    @Test fun whenTheServerStoresAVerifiedSwipeTheControlQueuesButNeverSaysDone() = runTest {
        val api = Api()
        val hinge = pairHinge(api)
        api.mock.operatorVerifySwipeForTest(hinge.id) // test fixture: no real platform has this
        val s = holder(api)
        s.decide(riley, ProfileDecision.LIKE)
        settle()
        assertEquals("Auto-swipe is off for Hinge (Settings).", s.card(riley).execute.explanation)
        assertFalse(s.card(riley).execute.enabled)
        api.mock.putConnectionSettings(hinge.id, ConnectionSettingsUpdate(true))
        s.load()
        settle()
        assertTrue(s.card(riley).execute.enabled)
        s.execute(riley)
        settle()
        assertEquals(1, api.executions.size)
        val card = s.card(riley)
        assertEquals("Decision recorded (Like) · Queued for Hinge, not done yet", card.statusLabel)
        assertFalse(card.execute.enabled)
        assertEquals("Queued for Hinge. Not done yet.", s.state.value.message)
        assertEquals(ProfileActionExecution.QUEUED, api.mock.profileActions().single().execution)
        assertTrue(allText(s).none { claimsCompletion.containsMatchIn(it) })
    }

    @Test fun aGateRefusalIsShownAsNotExecutable() = runTest {
        val api = Api()
        val hinge = pairHinge(api)
        api.mock.operatorVerifySwipeForTest(hinge.id)
        api.mock.putConnectionSettings(hinge.id, ConnectionSettingsUpdate(true))
        val s = holder(api)
        s.decide(riley, ProfileDecision.LIKE)
        settle()
        // The server pauses automation after the screen loaded: the gate refuses.
        api.mock.pauseAllAutomation(com.wingman.core.model.AutomationActionRequest(), IdempotencyKey("pause-0000001"))
        s.execute(riley)
        settle()
        assertEquals("Not executable on Hinge: automation is paused.", s.state.value.message)
        assertEquals("Decision recorded (Like) · Not executable on Hinge", s.card(riley).statusLabel)
    }

    @Test fun noLabelForAnyExecutionStateClaimsThePlatformActionHappened() {
        val now = Instant.parse("2026-11-02T08:40:00Z")
        for (provider in ProfileProvider.entries) for (decision in ProfileDecision.entries) for (execution in ProfileActionExecution.entries) {
            val a = ProfileAction("a", "p", "c", decision, "key-00000001", execution, null, now, null, null, now)
            val label = DiscoveryCopy.statusLabel(a, provider)
            assertTrue(label, label.startsWith(DiscoveryCopy.SAVED_ONLY))
            assertFalse(label, claimsCompletion.containsMatchIn(label))
            assertFalse(label, label.contains("Liked") || label.contains("Swiped"))
        }
    }

    // ------------------------------------------------------------------ import

    @Test fun manualImportSendsOnlyWhatTheUserTyped() = runTest {
        val api = Api()
        api.mock.clearProfiles()
        val s = holder(api)
        assertTrue(s.state.value.cards.isEmpty())
        val attempt = ImportAttempt(IdempotencyKey("import-attempt-0001"), Instant.parse("2026-11-02T08:30:00.123Z"))
        val form = ManualImportForm(
            provider = ProfileProvider.TINDER,
            displayName = "Casey (fictional)",
            bio = "Climber and baker.",
            prompts = "Best travel story | Lost luggage, found friends\n",
            attributes = "Looking for: Long-term relationship\nAge: 29\nInterests: climbing, baking",
        )
        val parsed = ManualImport.parse(attempt, form) as ImportParse.Ok
        val body = parsed.request
        assertEquals(ProfileSource.MANUAL, body.source)
        assertEquals("manual-import-attempt-0001", body.externalProfileId)
        assertEquals(Instant.parse("2026-11-02T08:30:00Z"), body.sourceObservedAt)
        assertEquals(setOf("looking_for", "age", "interests"), body.attributes.keys)
        assertEquals(JsonPrimitive(29), body.attributes["age"])
        assertEquals(JsonArray(listOf(JsonPrimitive("climbing"), JsonPrimitive("baking"))), body.attributes["interests"])
        assertEquals("Lost luggage, found friends", body.prompts.single().answer)
        // The same attempt parses to the same body (a retry replays instead of duplicating).
        assertEquals(body, (ManualImport.parse(attempt, form) as ImportParse.Ok).request)
        var done: Boolean? = null
        s.import(body, attempt.key) { done = it }
        settle()
        assertEquals(true, done)
        s.import(body, attempt.key)
        settle()
        val cards = s.state.value.cards
        assertEquals(1, cards.size)
        assertEquals("Tinder", cards.single().platformLabel)
        assertTrue(cards.single().unknowns.contains("Smoking"))
        assertFalse(cards.single().attributes.any { it.first == "smoking" })
    }

    @Test fun manualImportRejectsEmptyOrMalformedForms() {
        val now = Instant.parse("2026-11-02T08:30:00Z")
        assertTrue(ManualImport.parse(ManualImportForm(), now) is ImportParse.Invalid)
        val bad = ManualImport.parse(ManualImportForm(displayName = "X", attributes = "no colon here"), now) as ImportParse.Invalid
        assertEquals(listOf("Attribute lines look like: name: value"), bad.errors)
        assertTrue(ManualImport.parse(ManualImportForm(provider = ProfileProvider.MOCK, displayName = "X"), now) is ImportParse.Invalid)
        assertEquals("looking_for", ManualImport.attributeName(" Looking for "))
        assertNull(ManualImport.attributeName("9lives"))
    }

    @Test fun debugFixtureFeedImportsSixFictionalMockProfiles() = runTest {
        val api = Api()
        api.mock.clearProfiles()
        val s = holder(api)
        val feed = DiscoveryFixtures.feed(Instant.parse("2026-11-02T08:30:00Z"))
        assertEquals(6, feed.size)
        assertTrue(feed.all { it.provider == ProfileProvider.MOCK && it.source == ProfileSource.FIXTURE && it.displayName!!.contains("fictional") })
        s.importFixtures()
        settle()
        assertEquals(6, s.state.value.cards.size)
        assertTrue(s.state.value.cards.all { it.platformLabel == "Demo" })
        s.decide(s.state.value.cards.first().id, ProfileDecision.LIKE)
        settle()
        assertEquals("Demo profiles exist only in Wingman.", s.state.value.cards.first().execute.explanation)
        assertFalse(s.state.value.cards.first().execute.enabled)
    }

    @Test fun importRequestValidatesLocally() {
        val now = Instant.parse("2026-11-02T08:30:00Z")
        val ok = runCatching { ProfileImportRequest(ProfileProvider.HINGE, "x", ProfileSource.MANUAL, sourceObservedAt = now) }
        assertTrue(ok.isSuccess)
        assertTrue(runCatching { ProfileImportRequest(ProfileProvider.MOCK, "x", ProfileSource.MANUAL, sourceObservedAt = now) }.isFailure)
        assertTrue(runCatching { ProfileImportRequest(ProfileProvider.HINGE, "x", ProfileSource.DEVICE, sourceObservedAt = now) }.isFailure)
        assertTrue(runCatching { ProfileImportRequest(ProfileProvider.HINGE, "x", ProfileSource.MANUAL, attributes = mapOf("Bad Name" to JsonPrimitive(1)), sourceObservedAt = now) }.isFailure)
    }
}

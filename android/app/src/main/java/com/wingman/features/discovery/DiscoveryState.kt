package com.wingman.features.discovery

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.api.PageRequest
import com.wingman.core.api.ProfileQueueFilter
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.userMessage
import com.wingman.core.model.Connection
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Profile
import com.wingman.core.model.ProfileAction
import com.wingman.core.model.ProfileActionExecution
import com.wingman.core.model.ProfileDecision
import com.wingman.core.model.ProfileDecisionRequest
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ReasonEffect
import com.wingman.core.model.Verification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * WING-031: the Discovery review workflow. Pure Kotlin, JVM-tested; DiscoveryScreen.kt is the
 * Compose layer.
 *
 * What it is: a ranked queue of profiles the USER brought in (pasted by hand, or the debug fixture
 * feed), each with Wingman's advisory score, its reasons and the fields they cite, what is unknown,
 * the confidence, and a stale badge. The user records Like / Pass / Skip in Wingman.
 *
 * What it is NOT: a swipe. No connector can swipe on Tinder, Bumble or Hinge today
 * (docs/integrations/swiping.md), so a recorded decision stays in Wingman. The separate "Execute on
 * <platform>" control is disabled, with the reason, unless the server's stored state says the gate
 * could pass; even then success is "queued", which is not done. No label here ever says that
 * something was liked, passed or swiped on a platform.
 */

/** Plain-language copy. Nothing here claims a platform action happened. */
object DiscoveryCopy {
    const val TITLE = "Discovery"
    const val EXPLANATION =
        "Profiles you added, ranked by how well they fit what you're looking for. Like, Pass and Skip are saved in " +
            "Wingman only: Wingman can't swipe on Tinder, Bumble or Hinge."
    const val SHOW_EXCLUDED = "Show excluded"
    const val EXCLUDED_BADGE = "Excluded by a dealbreaker"
    const val STALE_BADGE = "May be out of date"
    const val STALE_NOTE = "Last seen more than 14 days ago, or the source said it changed. It can't be acted on until re-imported."
    const val EMPTY = "No profiles to review. Add one you saw in a dating app."
    const val NOT_SCORED = "Not scored"
    const val SAVED_ONLY = "Decision recorded"
    const val ALREADY_DECIDED = "This profile was already decided."
    const val DECISION_SAVED = "Decision recorded in Wingman. Nothing was done on the dating app."

    fun platformLabel(p: ProfileProvider): String = when (p) {
        ProfileProvider.TINDER -> "Tinder"
        ProfileProvider.BUMBLE -> "Bumble"
        ProfileProvider.HINGE -> "Hinge"
        ProfileProvider.MOCK -> "Demo"
    }

    fun platformWire(p: ProfileProvider): String = when (p) {
        ProfileProvider.TINDER -> "tinder"
        ProfileProvider.BUMBLE -> "bumble"
        ProfileProvider.HINGE -> "hinge"
        ProfileProvider.MOCK -> "mock"
    }

    fun decisionLabel(d: ProfileDecision): String = when (d) {
        ProfileDecision.LIKE -> "Like"
        ProfileDecision.PASS -> "Pass"
        ProfileDecision.SKIP -> "Skip"
        ProfileDecision.SUPERLIKE -> "Superlike"
    }

    fun executeLabel(p: ProfileProvider): String = "Execute on ${platformLabel(p)}"

    /**
     * The status line for a recorded action. Built from the decision word and the EXECUTION state
     * separately, so it can never read "Liked on Tinder".
     */
    fun statusLabel(action: ProfileAction, provider: ProfileProvider): String {
        val platform = platformLabel(provider)
        val recorded = "$SAVED_ONLY (${decisionLabel(action.decision)})"
        return when (action.execution) {
            ProfileActionExecution.NOT_EXECUTABLE ->
                if (action.decision == ProfileDecision.SKIP) "$recorded · Back in the queue later" else "$recorded · Not executable on $platform"
            ProfileActionExecution.QUEUED -> "$recorded · Queued for $platform, not done yet"
            ProfileActionExecution.EXECUTED -> "$recorded · The $platform connector reported it (not checked by Wingman)"
            ProfileActionExecution.FAILED -> "$recorded · Not done on $platform (failed)"
            ProfileActionExecution.UNKNOWN -> "$recorded · Outcome unknown on $platform: treat it as not done"
        }
    }

    fun confidenceLabel(c: Double): String {
        val word = when {
            c >= 0.75 -> "high"
            c >= 0.4 -> "medium"
            else -> "low"
        }
        return "Confidence: $word"
    }

    fun scoreLabel(score: Double?): String = score?.let { "${it.toInt()} / 100" } ?: NOT_SCORED

    /** `attributes/smoking` -> "smoking", `prompts/0` -> "prompt 1", `bio` -> "bio". */
    fun citation(pointer: String): String {
        val head = pointer.substringBefore('/')
        val rest = pointer.substringAfter('/', "")
        return when (head) {
            "attributes" -> rest.replace('_', ' ')
            "prompts" -> rest.toIntOrNull()?.let { "prompt ${it + 1}" } ?: "prompts"
            "display_name" -> "name"
            else -> head
        }
    }

    fun attributeValue(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.content
        is JsonArray -> v.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
        else -> v.toString()
    }
}

/** Whether, and why not, the separate execute control is available. */
data class ExecuteControl(
    val label: String,
    val enabled: Boolean,
    val explanation: String,
)

data class ReasonRow(val text: String, val effect: ReasonEffect, val cites: List<String>)

/** One profile on screen. Bio, prompts and attributes are private: [toString] redacts them. */
data class ProfileCard(
    val id: String,
    val provider: ProfileProvider,
    val platformLabel: String,
    val name: String,
    val bio: String?,
    val prompts: List<Pair<String, String>>,
    val attributes: List<Pair<String, String>>,
    val scoreLabel: String,
    val confidenceLabel: String?,
    val reasons: List<ReasonRow>,
    val unknowns: List<String>,
    val stale: Boolean,
    val excluded: Boolean,
    val dealbreakers: List<String>,
    val decision: ProfileDecision?,
    val statusLabel: String?,
    val busy: Boolean,
    val execute: ExecuteControl,
) {
    override fun toString(): String = "ProfileCard(id=$id, provider=$provider, stale=$stale, excluded=$excluded, decision=$decision)"
}

data class DiscoveryUi(
    val loading: Boolean = true,
    val cards: List<ProfileCard> = emptyList(),
    val showExcluded: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val importing: Boolean = false,
)

/**
 * State holder for the Discovery screen.
 *
 * - [decide]: one idempotency key per tap ([IdempotencyKeyRegistry], action `decide:<id>:<decision>`).
 *   A second tap while one runs is dropped; a retry after a network error reuses the key; the server
 *   replays the same action, so a double tap never records two decisions.
 * - [execute] is separate and only runs when [ExecuteControl.enabled]; the server still decides.
 * - Nothing here ever sets or shows a platform action as completed.
 */
class DiscoveryState(
    private val scope: CoroutineScope,
    private val api: WingmanApi,
    private val keys: IdempotencyKeyRegistry = IdempotencyKeyRegistry(),
    private val now: () -> java.time.Instant = java.time.Instant::now,
) {
    private val _state = MutableStateFlow(DiscoveryUi())
    val state: StateFlow<DiscoveryUi> = _state.asStateFlow()

    private val profiles = LinkedHashMap<String, Profile>()
    private val actions = HashMap<String, ProfileAction>()
    private var connections: List<Connection> = emptyList()
    private val inFlight = HashSet<String>()
    private var loadJob: Job? = null

    fun load() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        val showExcluded = _state.value.showExcluded
        loadJob = scope.launch {
            val queue = api.listProfileReviewQueue(PageRequest(limit = PAGE), ProfileQueueFilter(includeExcluded = showExcluded))
            (api.listConnections(PageRequest(limit = 100)) as? ApiResult.Success)?.let { connections = it.value.items }
            when (queue) {
                is ApiResult.Success -> {
                    // Keep cards the user just decided in this session (they show the recorded status).
                    val decided = profiles.values.filter { p -> actions[p.id]?.let { it.decision != ProfileDecision.SKIP } == true }
                    profiles.clear()
                    (queue.value.items + decided).forEach { profiles[it.id] = it }
                    queue.value.items.forEach { p -> p.latestAction?.let { a -> actions.putIfAbsent(p.id, a) } }
                    _state.update { it.copy(loading = false, error = null, cards = cards()) }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = queue.error.userMessage()) }
            }
        }
    }

    fun setShowExcluded(show: Boolean) {
        if (_state.value.showExcluded == show) return
        loadJob?.cancel()
        _state.update { it.copy(showExcluded = show) }
        load()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Records the user's Like / Pass / Skip in Wingman. Never executes anything. */
    fun decide(profileId: String, decision: ProfileDecision) {
        val profile = profiles[profileId] ?: return
        if (decision == ProfileDecision.SUPERLIKE) return // not offered on this screen
        if (!inFlight.add(profileId)) return // double tap: one request, one key
        val action = "decide:$profileId:${decision.name}"
        val key = keys.keyFor(action)
        _state.update { it.copy(cards = cards(), message = null) }
        scope.launch {
            val body = ProfileDecisionRequest(decision, connectionFor(profile.provider)?.id)
            val r = api.recordProfileDecision(profileId, body, key)
            inFlight.remove(profileId)
            when (r) {
                is ApiResult.Success -> {
                    keys.complete(action)
                    actions[profileId] = r.value
                    _state.update { it.copy(cards = cards(), message = DiscoveryCopy.DECISION_SAVED) }
                }
                is ApiResult.Failure -> failed(action, r.error, "Couldn't record that")
            }
        }
    }

    /** Asks the server's gate to accept the recorded decision. Only when the control is enabled. */
    fun execute(profileId: String) {
        val profile = profiles[profileId] ?: return
        val recorded = actions[profileId] ?: return
        if (!executeControl(profile, recorded).enabled) return
        if (!inFlight.add(profileId)) return
        val action = "execute:${recorded.id}"
        val key = keys.keyFor(action)
        _state.update { it.copy(cards = cards(), message = null) }
        scope.launch {
            val r = api.requestProfileActionExecution(recorded.id, key)
            inFlight.remove(profileId)
            when (r) {
                is ApiResult.Success -> {
                    keys.complete(action)
                    actions[profileId] = r.value
                    _state.update { it.copy(cards = cards(), message = "Queued for ${DiscoveryCopy.platformLabel(profile.provider)}. Not done yet.") }
                }
                is ApiResult.Failure -> {
                    val e = r.error
                    if (e.code == ErrorCode.SWIPE_EXECUTION_DISABLED) {
                        keys.complete(action)
                        val why = (e as? ApiError.Http)?.problem?.errors?.firstOrNull()?.message
                        val platform = DiscoveryCopy.platformLabel(profile.provider)
                        // The server's stored state changed (e.g. auto-swipe off): reload connections.
                        (api.listConnections(PageRequest(limit = 100)) as? ApiResult.Success)?.let { connections = it.value.items }
                        _state.update {
                            it.copy(cards = cards(), message = "Not executable on $platform" + (why?.let { w -> ": $w." } ?: "."))
                        }
                    } else {
                        failed(action, e, "Couldn't ask for that")
                    }
                }
            }
        }
    }

    /** Imports a profile the user pasted (or a debug fixture). [key] is one per submit. */
    fun import(body: ProfileImportRequest, key: IdempotencyKey, onDone: (Boolean) -> Unit = {}) {
        _state.update { it.copy(importing = true, message = null) }
        scope.launch {
            val r = api.importProfile(body, key)
            _state.update { it.copy(importing = false) }
            when (r) {
                is ApiResult.Success -> {
                    _state.update { it.copy(message = "Added. It's in the queue below.") }
                    onDone(true)
                    load()
                }
                is ApiResult.Failure -> {
                    _state.update { it.copy(message = "Couldn't add it: ${r.error.userMessage()}") }
                    onDone(false)
                }
            }
        }
    }

    /** Debug only: imports the deterministic fictional feed through the normal import path. */
    fun importFixtures() {
        _state.update { it.copy(importing = true, message = null) }
        scope.launch {
            var added = 0
            for (body in DiscoveryFixtures.feed(now())) {
                val action = "fixture:${body.externalProfileId}"
                val r = api.importProfile(body, keys.keyFor(action))
                if (r is ApiResult.Success) {
                    keys.complete(action)
                    added++
                }
            }
            _state.update { it.copy(importing = false, message = "Added $added demo profiles (fictional).") }
            load()
        }
    }

    private fun failed(action: String, e: ApiError, prefix: String) {
        if (!e.isRetryable && e !is ApiError.Network) keys.complete(action)
        val text = when (e.code) {
            ErrorCode.RATE_LIMITED -> "You're deciding very fast. Wait a moment and try again."
            ErrorCode.NOT_FOUND -> "This profile no longer exists."
            else -> "$prefix: ${e.userMessage()} Try again."
        }
        _state.update { it.copy(cards = cards(), message = text) }
    }

    /** The user's paired connection on the profile's platform, if any (decisions name it). */
    private fun connectionFor(provider: ProfileProvider): Connection? =
        connections.firstOrNull { it.platform == DiscoveryCopy.platformWire(provider) && it.isPaired }

    /** Mirrors the server's gate from stored state only (never from a client claim). */
    internal fun executeControl(profile: Profile, action: ProfileAction?): ExecuteControl {
        val platform = DiscoveryCopy.platformLabel(profile.provider)
        val label = DiscoveryCopy.executeLabel(profile.provider)
        fun off(why: String) = ExecuteControl(label, enabled = false, explanation = why)
        val connection = connectionFor(profile.provider)
        val swipe = connection?.capabilities?.capabilities?.swipe?.status
        return when {
            profile.provider == ProfileProvider.MOCK -> off("Demo profiles exist only in Wingman.")
            action == null -> off("Record a decision first. Even then, Wingman can only save it.")
            action.decision == ProfileDecision.SKIP -> off("Skip is never sent to $platform.")
            action.execution == ProfileActionExecution.QUEUED -> off("Already queued. Not done on $platform yet.")
            action.execution != ProfileActionExecution.NOT_EXECUTABLE -> off("Already attempted.")
            connection == null || action.connectionId == null ->
                off("No $platform connection, and $platform has no tested swipe interface. Your decision is saved in Wingman only.")
            swipe != Verification.VERIFIED ->
                off("Wingman can't swipe on $platform: no tested way to do it exists (swipe is ${swipe?.name ?: "UNSUPPORTED"}). Your decision is saved in Wingman only.")
            connection.isExperimentalUnverified -> off("The experimental $platform connector is approval-only and never swipes.")
            connection.autoSwipeEnabled != true -> off("Auto-swipe is off for $platform (Settings).")
            profile.stale -> off("This profile may have changed on $platform. Add it again first.")
            else -> ExecuteControl(label, enabled = true, explanation = "Asks Wingman to queue it. Queued is not done.")
        }
    }

    private fun cards(): List<ProfileCard> = profiles.values.map { p ->
        val evaluation = p.latestEvaluation
        val action = actions[p.id]
        ProfileCard(
            id = p.id,
            provider = p.provider,
            platformLabel = DiscoveryCopy.platformLabel(p.provider),
            name = p.displayName ?: "(no name given)",
            bio = p.bio,
            prompts = p.prompts.map { it.prompt to it.answer },
            attributes = p.attributes.entries.sortedBy { it.key }.map { it.key.replace('_', ' ') to DiscoveryCopy.attributeValue(it.value) },
            scoreLabel = DiscoveryCopy.scoreLabel(evaluation?.score),
            confidenceLabel = evaluation?.let { DiscoveryCopy.confidenceLabel(it.confidence) },
            reasons = evaluation?.reasons.orEmpty().map { r -> ReasonRow(r.detail, r.effect, r.evidence.orEmpty().map(DiscoveryCopy::citation)) },
            unknowns = evaluation?.knownUnknowns.orEmpty(),
            stale = p.stale,
            excluded = !evaluation?.dealbreakersHit.isNullOrEmpty(),
            dealbreakers = evaluation?.dealbreakersHit.orEmpty(),
            decision = action?.decision,
            statusLabel = action?.let { DiscoveryCopy.statusLabel(it, p.provider) },
            busy = p.id in inFlight,
            execute = executeControl(p, action),
        )
    }

    companion object {
        const val PAGE = 50
    }
}

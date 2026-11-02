package com.wingman.features.discovery

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.userMessage
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionSettingsUpdate
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Verification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * WING-031: Settings > Auto-swipe, one toggle per dating-app connection. The toggle can be turned
 * on only when the SERVER stores a VERIFIED swipe for that connection and it is not an unverified
 * experimental connector (the same gate `putConnectionSettings` enforces). No dating app has one
 * today, so every toggle is disabled with the reason. Turning it off always works. Pure Kotlin.
 */

data class AutoSwipeRow(
    val connectionId: String,
    val platformLabel: String,
    val checked: Boolean,
    /** False unless the server's stored swipe is VERIFIED (and turning it off is always allowed). */
    val toggleEnabled: Boolean,
    val explanation: String,
    val busy: Boolean = false,
)

data class AutoSwipeUi(
    val loading: Boolean = true,
    val rows: List<AutoSwipeRow> = emptyList(),
    val error: String? = null,
    val message: String? = null,
) {
    val emptyExplanation: String get() = AutoSwipeCopy.NO_CONNECTIONS
}

object AutoSwipeCopy {
    /** Settings shows the section collapsed to this one line; nothing is loaded until it is opened. */
    const val COLLAPSED_SUMMARY = "Off on every dating app: no tested swipe exists."
    const val INTRO =
        "Auto-swipe would let Wingman act on your recorded decisions in a dating app. It stays off until a real-device " +
            "test shows a working swipe. None of Tinder, Bumble or Hinge has one."
    const val NO_CONNECTIONS = "No dating-app connections. Auto-swipe isn't available on Tinder, Bumble or Hinge."
    val DATING = setOf("tinder", "bumble", "hinge")

    fun platformLabel(p: String) = when (p) {
        "tinder" -> "Tinder"
        "bumble" -> "Bumble"
        "hinge" -> "Hinge"
        else -> p
    }

    /** Why the toggle is (or is not) available, from the server's stored state only. */
    fun gate(c: Connection): Pair<Boolean, String> {
        val platform = platformLabel(c.platform)
        val swipe = c.capabilities.capabilities.swipe.status
        return when {
            !c.isPaired -> false to "This connection is unpaired."
            swipe != Verification.VERIFIED ->
                false to "Off: $platform swipe is $swipe. No tested way to swipe on $platform exists, so Wingman only records your decisions."
            c.isExperimentalUnverified -> false to "Off: the experimental $platform connector is approval-only and never swipes."
            else -> true to "$platform swipe was verified on a real device. Each request is still checked and limited."
        }
    }
}

class AutoSwipeState(private val scope: CoroutineScope, private val api: WingmanApi) {
    private val _state = MutableStateFlow(AutoSwipeUi())
    val state: StateFlow<AutoSwipeUi> = _state.asStateFlow()
    private var connections: List<Connection> = emptyList()
    private val inFlight = HashSet<String>()
    private var loadJob: Job? = null

    fun load() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            when (val r = api.listConnections(PageRequest(limit = 100))) {
                is ApiResult.Success -> {
                    connections = r.value.items.filter { it.platform in AutoSwipeCopy.DATING && it.isPaired }
                    _state.update { it.copy(loading = false, rows = rows()) }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    /** Turning on is refused locally when the stored gate is closed; the server checks again anyway. */
    fun toggle(connectionId: String, on: Boolean) {
        val c = connections.firstOrNull { it.id == connectionId } ?: return
        if (on && !AutoSwipeCopy.gate(c).first) return
        if (!inFlight.add(connectionId)) return
        _state.update { it.copy(rows = rows(), message = null) }
        scope.launch {
            val r = api.putConnectionSettings(connectionId, ConnectionSettingsUpdate(autoSwipeEnabled = on))
            inFlight.remove(connectionId)
            when (r) {
                is ApiResult.Success -> {
                    connections = connections.map { if (it.id == connectionId) r.value else it }
                    _state.update { it.copy(rows = rows()) }
                }
                is ApiResult.Failure -> {
                    val e = r.error
                    val why = if (e.code == ErrorCode.SWIPE_EXECUTION_DISABLED) {
                        (e as? ApiError.Http)?.problem?.errors?.firstOrNull()?.message ?: "not available"
                    } else {
                        e.userMessage()
                    }
                    _state.update { it.copy(rows = rows(), message = "Couldn't change auto-swipe: $why") }
                }
            }
        }
    }

    private fun rows(): List<AutoSwipeRow> = connections.map { c ->
        val (open, why) = AutoSwipeCopy.gate(c)
        val checked = c.autoSwipeEnabled == true
        AutoSwipeRow(
            connectionId = c.id,
            platformLabel = AutoSwipeCopy.platformLabel(c.platform),
            checked = checked,
            // Off is always allowed: a checked toggle stays usable so the user can turn it off.
            toggleEnabled = (open || checked) && c.id !in inFlight,
            explanation = why,
            busy = c.id in inFlight,
        )
    }
}

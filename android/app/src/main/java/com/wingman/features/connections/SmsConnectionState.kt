package com.wingman.features.connections

import com.wingman.core.api.userMessage
import com.wingman.core.model.Capability
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionStatus
import com.wingman.core.model.ConnectionUnpairResult
import com.wingman.core.model.Verification
import com.wingman.integrations.sms.SmsCapabilities
import com.wingman.integrations.sms.SmsClock
import com.wingman.integrations.sms.SmsConnectorHost
import com.wingman.integrations.sms.SmsDeviceState
import com.wingman.integrations.sms.SmsLocalStatus
import com.wingman.integrations.sms.SmsPairResult
import com.wingman.integrations.sms.SmsPairingState
import com.wingman.integrations.sms.SmsUnpairResult
import com.wingman.integrations.sms.SmsVerifyResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/*
 * I-020: "Phone SMS" connection section. Pure Kotlin, JVM-tested; SmsConnectionScreen.kt is the
 * Compose layer. Every status shown here comes from the server's stored connection, never from what
 * this app claimed when pairing.
 */

/** What the user still has to do on the phone before pairing. */
enum class SmsPairStep {
    /** No telephony: SMS can't be used on this device at all. */
    NO_TELEPHONY,
    NEEDS_ROLE,
    NEEDS_PERMISSIONS,
    READY,
}

/** One capability as the server stored it. */
data class CapabilityRow(val name: String, val status: Verification, val label: String, val explanation: String)

data class SmsConnectionView(
    val paired: Boolean,
    val connectionId: String?,
    val displayName: String?,
    val statusLabel: String,
    val capabilities: List<CapabilityRow>,
    /** True ONLY when the connection is connected and the server stored `send` as VERIFIED (D-016). */
    val autopilotAvailable: Boolean,
    val sendingNote: String?,
    /** Why the user must pair again, e.g. unpaired elsewhere. */
    val notice: String?,
    /** A disconnected connection the server still binds to this install: offer to unpair it. */
    val staleConnectionId: String?,
    val local: SmsLocalStatus,
    val lastSyncLabel: String,
)

/** Plain-language copy and the display rules. */
object SmsConnectionCopy {
    /** Test tag (and UiAutomator resource id) of the "Status: ..." line. */
    const val STATUS_TEST_TAG = "sms_connection_status"
    const val ROLE_EXPLANATION =
        "Android only lets the default SMS app receive texts and keep them in your message history. " +
            "Wingman needs to be your default SMS app to see incoming texts and to send the replies you approve. " +
            "While it is, MMS (picture messages) won't work in Wingman. You can switch back at any time in Android Settings."

    const val PERMISSIONS_EXPLANATION =
        "Wingman asks for: receive SMS (to see new texts), send SMS (to send replies you approve) and read SMS " +
            "(to keep your inbox consistent). Texts are uploaded to your Wingman account so it can suggest replies. " +
            "Wingman never replies to anyone on its own from this screen."

    const val PAIR_EXPLANATION =
        "Pairing links this phone's SMS to your Wingman account. Until a Wingman operator has verified sending on a " +
            "real device, every reply sent by SMS needs your approval first."

    const val SEND_UNVERIFIED =
        "Sending by SMS is UNVERIFIED: it hasn't been verified on a real device yet, and only a Wingman operator with " +
            "evidence from a device test can verify it. Until then every SMS reply needs your approval, and autopilot is not available for SMS."

    const val SEND_UNSUPPORTED = "Wingman can't send SMS on this connection. Replies have to be sent from another app."

    const val SEND_VERIFIED = "Sending by SMS is VERIFIED by a Wingman operator. Autopilot can be used for SMS if you turn it on."

    const val NO_TELEPHONY = "This device can't send or receive SMS, so it can't be paired."

    const val UNPAIR_TITLE = "Disconnect this phone's SMS?"

    const val UNPAIR_BODY =
        "Wingman will stop reading and sending texts on this phone. Messages that are still queued to send " +
            "(including ones you wrote or approved) and drafts waiting for your approval in these SMS conversations " +
            "will be canceled. A message this phone has " +
            "already started sending can't be recalled; its result is still recorded. Your conversation history stays. " +
            "Texts already received but not yet uploaded are kept and uploaded later if Wingman accepts them."

    fun statusLabel(c: Connection?): String = when {
        c == null -> "Paired (status not loaded yet)"
        c.unpairedAt != null -> "Disconnected (unpaired)"
        c.status == ConnectionStatus.CONNECTED -> "Connected"
        c.status == ConnectionStatus.DISCONNECTED -> "Disconnected"
        else -> "Error: Wingman reports a problem with this connection"
    }

    fun verificationLabel(v: Verification): String = when (v) {
        Verification.VERIFIED -> "VERIFIED"
        Verification.UNVERIFIED -> "UNVERIFIED (not yet tested on a device)"
        Verification.UNSUPPORTED -> "Not supported"
    }

    fun lastSync(lastAt: Long?, now: Long): String {
        if (lastAt == null) return "Never synced"
        val s = ((now - lastAt) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "Last sync just now"
            s < 3600 -> "Last sync ${s / 60} min ago"
            s < 86_400 -> "Last sync ${s / 3600} h ago"
            else -> "Last sync ${s / 86_400} d ago"
        }
    }
}

object SmsConnectionPresenter {

    fun pairStep(d: SmsDeviceState): SmsPairStep = when {
        !d.hasTelephony -> SmsPairStep.NO_TELEPHONY
        !d.isDefaultSmsApp -> SmsPairStep.NEEDS_ROLE
        !d.receiveSmsGranted || !d.sendSmsGranted -> SmsPairStep.NEEDS_PERMISSIONS
        else -> SmsPairStep.READY
    }

    /** Autopilot needs a connected connection whose STORED `send` is VERIFIED (ADR 0005, D-016). */
    fun autopilotAvailable(c: Connection?): Boolean =
        c != null && c.isPaired && c.status == ConnectionStatus.CONNECTED &&
            c.capabilities.capabilities.send.status == Verification.VERIFIED

    fun capabilityRows(c: Connection): List<CapabilityRow> {
        val s = c.capabilities.capabilities
        fun row(name: String, cap: Capability, what: String) =
            CapabilityRow(name, cap.status, SmsConnectionCopy.verificationLabel(cap.status), cap.notes ?: what)
        return listOf(
            row("Read incoming texts", s.read, "Receiving texts on this phone."),
            row("Send replies", s.send, "Sending SMS replies from this phone."),
            row("Draft into another app", s.draft, "SMS never places drafts into another app."),
            row("Swipe", s.swipe, "Not applicable to SMS."),
            row("Add to calendar", s.calendarWrite, "Not applicable to SMS."),
        )
    }

    fun sendingNote(c: Connection): String = when (c.capabilities.capabilities.send.status) {
        Verification.VERIFIED -> if (autopilotAvailable(c)) SmsConnectionCopy.SEND_VERIFIED else SmsConnectionCopy.SEND_UNSUPPORTED
        Verification.UNVERIFIED -> SmsConnectionCopy.SEND_UNVERIFIED
        Verification.UNSUPPORTED -> SmsConnectionCopy.SEND_UNSUPPORTED
    }

    fun view(state: SmsPairingState, local: SmsLocalStatus, nowMillis: Long): SmsConnectionView = when (state) {
        is SmsPairingState.Unpaired -> SmsConnectionView(
            paired = false, connectionId = null, displayName = null, statusLabel = "Not paired",
            capabilities = emptyList(), autopilotAvailable = false, sendingNote = null,
            notice = state.notice, staleConnectionId = state.staleConnectionId,
            local = local, lastSyncLabel = SmsConnectionCopy.lastSync(local.lastSyncAtMillis, nowMillis),
        )
        is SmsPairingState.Paired -> {
            val c = state.connection
            SmsConnectionView(
                paired = true, connectionId = state.scope.connectionId, displayName = c?.displayName,
                statusLabel = SmsConnectionCopy.statusLabel(c),
                capabilities = c?.let(::capabilityRows).orEmpty(),
                autopilotAvailable = autopilotAvailable(c),
                // Not loaded yet: still say approval is needed; never imply autopilot.
                sendingNote = c?.let(::sendingNote) ?: SmsConnectionCopy.SEND_UNVERIFIED,
                notice = null, staleConnectionId = null,
                local = local, lastSyncLabel = SmsConnectionCopy.lastSync(local.lastSyncAtMillis, nowMillis),
            )
        }
    }
}

enum class SmsBusy { CHECKING, PAIRING, UNPAIRING }

data class SmsConnectionsUi(
    val view: SmsConnectionView,
    val step: SmsPairStep,
    val busy: SmsBusy? = null,
    /** Result or error of the last action, in plain language. */
    val message: String? = null,
    val confirmingUnpair: Boolean = false,
    val lastUnpair: ConnectionUnpairResult? = null,
    /** False while no backend client is connected (pairing is impossible). */
    val backendAvailable: Boolean = true,
)

/**
 * State holder for the section. [device] reads the phone (role, permissions); [afterChange] runs
 * after a pair or unpair changed the active connection (the Android layer starts a sync round).
 * Pair, verify and unpair run in [io] under [SmsConnectorHost.exclusive], so they never overlap a
 * sync round (blocking: [io] must be a background dispatcher on Android).
 */
class SmsConnectionsState(
    private val scope: CoroutineScope,
    private val host: SmsConnectorHost,
    private val device: () -> SmsDeviceState,
    private val displayName: String,
    private val clock: SmsClock = SmsClock.SYSTEM,
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val afterChange: () -> Unit = {},
) {
    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<SmsConnectionsUi> = _state

    private fun snapshot(busy: SmsBusy? = null, message: String? = null, prev: SmsConnectionsUi? = null): SmsConnectionsUi {
        val pairing = host.pairing()
        val pairingState = pairing?.state() ?: SmsPairingState.Unpaired()
        return SmsConnectionsUi(
            view = SmsConnectionPresenter.view(pairingState, host.localStatus(), clock.nowMillis()),
            step = SmsConnectionPresenter.pairStep(device()),
            busy = busy,
            message = message ?: prev?.message,
            confirmingUnpair = prev?.confirmingUnpair ?: false,
            lastUnpair = prev?.lastUnpair,
            backendAvailable = pairing != null,
        )
    }

    /** Re-reads local state and the phone's role/permissions (e.g. after a system dialog). */
    fun refresh() {
        _state.value = snapshot(busy = _state.value.busy, prev = _state.value)
    }

    private fun begin(busy: SmsBusy): Boolean {
        if (_state.value.busy != null) return false // drop a second tap while one action runs
        _state.update { it.copy(busy = busy, message = null) }
        return true
    }

    private fun finish(message: String?, lastUnpair: ConnectionUnpairResult? = _state.value.lastUnpair) {
        _state.value = snapshot(prev = _state.value.copy(confirmingUnpair = false, lastUnpair = lastUnpair, message = message))
    }

    /** `getConnection` on the stored ID; handles unpaired elsewhere / 404 / disconnected. */
    fun verify() {
        val pairing = host.pairing() ?: return refresh()
        if (!begin(SmsBusy.CHECKING)) return
        scope.launch {
            val r = withContext(io) { host.exclusive { runBlocking { pairing.verify() } } }
            val msg = when (r) {
                is SmsVerifyResult.NeedsRePair -> r.reason
                is SmsVerifyResult.Unknown -> "Couldn't check the connection: ${r.error.userMessage()}"
                else -> null
            }
            finish(msg)
        }
    }

    fun pair() {
        val pairing = host.pairing() ?: return finish("Wingman isn't connected to a server, so pairing isn't possible.")
        val d = device()
        if (SmsConnectionPresenter.pairStep(d) != SmsPairStep.READY) return refresh()
        if (!begin(SmsBusy.PAIRING)) return
        scope.launch {
            val r = withContext(io) { host.exclusive { runBlocking { pairing.pair(displayName, SmsCapabilities.build(d, clock.nowMillis())) } } }
            when (r) {
                is SmsPairResult.Paired -> {
                    finish(if (r.replayed) "This phone was already paired; using that connection." else "Paired.")
                    afterChange()
                }
                is SmsPairResult.ServerDisconnected ->
                    finish("Wingman reports this phone's previous SMS connection as disconnected. Disconnect it, then pair again.")
                is SmsPairResult.Failed -> finish(
                    "Pairing failed: ${r.error.userMessage()}" + if (r.retryable) " Try again; it won't create a duplicate." else "",
                )
            }
        }
    }

    fun requestUnpair() = _state.update { it.copy(confirmingUnpair = true) }

    fun cancelUnpair() = _state.update { it.copy(confirmingUnpair = false) }

    /** Called from the confirmation dialog only. */
    fun confirmUnpair() {
        val pairing = host.pairing() ?: return finish("Wingman isn't connected to a server, so disconnecting isn't possible.")
        _state.update { it.copy(confirmingUnpair = false) }
        if (!begin(SmsBusy.UNPAIRING)) return
        scope.launch {
            when (val r = withContext(io) { host.exclusive { runBlocking { pairing.unpair() } } }) {
                is SmsUnpairResult.Unpaired -> {
                    finish(unpairSummary(r.result), lastUnpair = r.result)
                    afterChange()
                }
                SmsUnpairResult.NotPaired -> finish("Nothing to disconnect.")
                is SmsUnpairResult.Failed -> finish("Couldn't disconnect: ${r.error.userMessage()} The phone is still paired; try again.")
            }
        }
    }

    companion object {
        fun unpairSummary(r: ConnectionUnpairResult?): String {
            if (r == null) return "Disconnected. Wingman no longer had this connection."
            val parts = buildList {
                if (r.canceledMessageCount > 0) add("${r.canceledMessageCount} queued message(s) canceled")
                if (r.canceledApprovalCount > 0) add("${r.canceledApprovalCount} pending approval(s) canceled")
                if (r.inFlightMessageCount > 0) add("${r.inFlightMessageCount} message(s) already being sent couldn't be recalled")
            }
            return "Disconnected." + if (parts.isEmpty()) "" else " " + parts.joinToString("; ") + "."
        }
    }
}

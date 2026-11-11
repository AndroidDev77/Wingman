package com.wingman.integrations.notifications

/** ADR 0005 labels. Code never produces VERIFIED on this path: only a recorded device run can (D-016). */
enum class CapabilityStatus { VERIFIED, UNVERIFIED, UNSUPPORTED }

/** One capability with a short, non-private note. */
data class CapabilityState(val status: CapabilityStatus, val note: String? = null)

/**
 * What the notification path can do for one platform right now. `profileRead` is listed because
 * WING-027..029 track it, although `connector_capabilities.v1` has no slot for it yet
 * ([NotificationContractMapping] drops it until the contract adds one).
 */
data class NotificationPlatformCapabilities(
    val platform: String,
    val read: CapabilityState,
    val draft: CapabilityState,
    val send: CapabilityState,
    val swipe: CapabilityState,
    val profileRead: CapabilityState,
    val calendarWrite: CapabilityState,
)

/**
 * D-032 shared interface: who else on this phone can read profiles for a platform's connection.
 * Returns null when it has nothing to say (the notification path's own UNSUPPORTED stands). It can
 * never return VERIFIED into the wire document: [NotificationContractMapping] downgrades that.
 */
fun interface ProfileReadCapabilitySource {
    fun profileRead(platform: String): CapabilityState?
}

/** The registered [ProfileReadCapabilitySource]; null in every standard build. */
object ProfileReadCapability {
    @Volatile var source: ProfileReadCapabilitySource? = null

    fun apply(c: NotificationPlatformCapabilities): NotificationPlatformCapabilities {
        val extra = runCatching { source?.profileRead(c.platform) }.getOrNull() ?: return c
        return c.copy(profileRead = extra)
    }
}

/** Inputs to [NotificationCapabilityModel.assess]; all observable on the device. */
data class NotificationCapabilityInputs(
    val pluginRegistered: Boolean,
    val accessGranted: Boolean,
    val readEnabledByUser: Boolean,
    val directReplyEnabledByUser: Boolean,
    val remoteInputObserved: Boolean,
)

/**
 * Capability rules of the notification path (ADR 0005, ticket WING-017):
 * - `read`: UNVERIFIED while access is granted, a plugin exists and the user enabled the platform;
 *   UNSUPPORTED otherwise (access revoked is a downgrade, reported at once).
 * - `send`: UNSUPPORTED until a free-form RemoteInput was observed on an authentic notification
 *   AND the user enabled direct reply for the platform; then UNVERIFIED (never autopilot-eligible).
 * - `draft`, `swipe`, `profile_read`, `calendar_write`: UNSUPPORTED on this path, always.
 *   No accessibility-service tapping exists or will be added.
 *
 * D-032: `profile_read` of a platform's connection can come from ANOTHER reader on the same phone
 * ([ProfileReadCapability], set only by the experimental build's read-only screen reader). The
 * notification path itself still never reads a profile, and that reader never taps either.
 */
object NotificationCapabilityModel {
    const val NOTE_NO_PLUGIN = "No notification plugin for this platform."
    const val NOTE_ACCESS_OFF = "Notification access is off."
    const val NOTE_READ_OFF = "Reading this app's notifications is turned off."
    const val NOTE_READ_PENDING = "Reads notification previews only; pending a physical-device test (WING-017)."
    const val NOTE_NO_REMOTE_INPUT = "No RemoteInput reply action observed on this app's notifications."
    const val NOTE_REPLY_OFF = "Direct reply is turned off for this app."
    const val NOTE_SEND_PENDING = "Reply through the notification's RemoteInput; records 'submitted' only. Pending a device test."
    const val NOTE_NOT_ON_PATH = "Not available through notifications."

    fun assess(platform: String, i: NotificationCapabilityInputs): NotificationPlatformCapabilities {
        val unsupported = CapabilityState(CapabilityStatus.UNSUPPORTED, NOTE_NOT_ON_PATH)
        val read = when {
            !i.pluginRegistered -> CapabilityState(CapabilityStatus.UNSUPPORTED, NOTE_NO_PLUGIN)
            !i.accessGranted -> CapabilityState(CapabilityStatus.UNSUPPORTED, NOTE_ACCESS_OFF)
            !i.readEnabledByUser -> CapabilityState(CapabilityStatus.UNSUPPORTED, NOTE_READ_OFF)
            else -> CapabilityState(CapabilityStatus.UNVERIFIED, NOTE_READ_PENDING)
        }
        val send = when {
            read.status == CapabilityStatus.UNSUPPORTED -> CapabilityState(CapabilityStatus.UNSUPPORTED, read.note)
            !i.remoteInputObserved -> CapabilityState(CapabilityStatus.UNSUPPORTED, NOTE_NO_REMOTE_INPUT)
            !i.directReplyEnabledByUser -> CapabilityState(CapabilityStatus.UNSUPPORTED, NOTE_REPLY_OFF)
            else -> CapabilityState(CapabilityStatus.UNVERIFIED, NOTE_SEND_PENDING)
        }
        return NotificationPlatformCapabilities(
            platform = platform,
            read = read,
            draft = unsupported,
            send = send,
            swipe = unsupported,
            profileRead = unsupported,
            calendarWrite = unsupported,
        )
    }
}

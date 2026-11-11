package com.wingman.integrations.screenreader

import com.wingman.integrations.notifications.NotificationClock
import java.util.UUID

/** What [ScreenReaderCore.onSnapshot] did with one snapshot. Carries no text. */
enum class SnapshotOutcome { DROPPED, INSPECTED, PROFILE_PART, CHAT_CONTEXT, MATCH_LIST, IGNORED }

/**
 * The pure-Kotlin heart of the screen reader. The service hands it snapshots; it re-checks the
 * allowlist and the user's toggles, records the redacted inspector snapshot, classifies the screen,
 * merges profile fragments while the user stays on a profile and saves the profile on the device
 * when they leave it. It talks to no network and performs no action in any app: it only reads what
 * it is handed.
 */
class ScreenReaderCore(
    val settings: ScreenReaderSettings,
    val inspector: InspectorLog,
    val profiles: CapturedProfileStore,
    private val clock: NotificationClock = NotificationClock.SYSTEM,
    private val log: ScreenReaderLog = ScreenReaderLog.NONE,
    private val screenProfiles: (String) -> ScreenProfile? = ScreenProfile.Companion::forPackage,
    /** Contact names of the platform's conversations, as last loaded (may be empty). */
    private val conversationNames: (platform: String) -> Collection<String> = { emptyList() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onCaptured: (CapturedProfile) -> Unit = {},
) {
    val gate = ScreenReaderGate(settings)

    private var session: ProfileCaptureSession? = null
    private var sessionPackage: String? = null
    private var lastChat: ChatContextSnapshot? = null
    private var lastChatPackage: String? = null
    private var listNames: List<String> = emptyList()

    /** Asked by the service BEFORE it touches a window. */
    fun mode(packageName: String?): ReadMode = gate.mode(packageName)

    @Synchronized
    fun onSnapshot(s: ScreenSnapshot): SnapshotOutcome {
        // The in-code allowlist and toggle check, whatever the caller did.
        val mode = gate.mode(s.packageName)
        if (!mode.reads) {
            discardIfFor(s.packageName)
            return SnapshotOutcome.DROPPED
        }
        val sp = screenProfiles(s.packageName)
        val classification = sp?.let { ScreenClassifier.classify(s, it) } ?: ScreenClassification(ScreenKind.OTHER, null)
        if (mode.inspect) inspector.record(ScreenInspector.redact(s, classification.kind))
        if (!mode.capture || sp == null) {
            discardIfFor(s.packageName)
            return SnapshotOutcome.INSPECTED
        }
        // A long pause means the earlier viewing is over.
        session?.let { if (s.capturedAtMillis - it.lastSeenMillis > STALE_MS || sessionPackage != s.packageName) finish() }
        return when (classification.kind) {
            ScreenKind.PROFILE -> {
                val fragment = ProfileExtractor.extract(s, sp)
                session?.let { if (!it.accepts(fragment)) finish() }
                val current = session ?: ProfileCaptureSession(s.capturedAtMillis).also {
                    session = it
                    sessionPackage = s.packageName
                }
                current.merge(fragment, s.capturedAtMillis)
                log.log("sr.profile_part", mapOf("prompts" to fragment.prompts.size.toString(), "basics" to fragment.basics.size.toString(), "source" to classification.source.toString()))
                SnapshotOutcome.PROFILE_PART
            }
            ScreenKind.CHAT -> {
                // Context only: which match is open. Never a message, never a draft trigger.
                lastChat = ChatScreenReader.read(s, sp)
                lastChatPackage = s.packageName
                finish()
                log.log("sr.chat_context", mapOf("lines" to (lastChat?.lines?.size ?: 0).toString()))
                SnapshotOutcome.CHAT_CONTEXT
            }
            ScreenKind.MATCH_LIST -> {
                finish()
                listNames = ScreenText.pairs(s.root).filter { it.containerClickable }.map { it.upper.text }
                SnapshotOutcome.MATCH_LIST
            }
            ScreenKind.OTHER -> {
                finish()
                SnapshotOutcome.IGNORED
            }
        }
    }

    /** Called a little after the last snapshot: the user stopped looking, so the viewing is over. */
    @Synchronized
    fun onIdle(nowMillis: Long = clock.nowMillis()) {
        val s = session ?: return
        if (nowMillis - s.lastSeenMillis >= IDLE_FINISH_MS) finish()
    }

    /** Finishes the current viewing now (the Wingman screen opened, the service is going away). */
    @Synchronized
    fun flush() = finish()

    /** A toggle was switched off: whatever was being read for [packageName] is thrown away. */
    @Synchronized
    fun stop(packageName: String) {
        discardIfFor(packageName)
        if (lastChatPackage == packageName) {
            lastChat = null
            lastChatPackage = null
            listNames = emptyList()
        }
    }

    /** The chat context of the last chat screen seen (for tests and the match key). */
    @Synchronized
    fun lastChatContext(): ChatContextSnapshot? = lastChat

    private fun discardIfFor(packageName: String) {
        if (sessionPackage == packageName) {
            session = null
            sessionPackage = null
        }
    }

    private fun finish() {
        val s = session ?: return
        val pkg = sessionPackage
        session = null
        sessionPackage = null
        val merged = s.result() ?: return
        val platform = pkg?.let { ScreenReaderApps.platformOf(it) } ?: return
        // Reading stopped in the meantime: nothing more is kept.
        if (!gate.mode(pkg).capture) return
        val chatName = lastChat?.takeIf { lastChatPackage == pkg && merged.lastSeenMillis - it.capturedAtMillis in -CHAT_CONTEXT_MS..CHAT_CONTEXT_MS }?.headerName
        val names = listOf(listNames, conversationNames(platform))
        val saved = profiles.saveCapture(
            merged, platform,
            key = { sameNameOther -> MatchKeys.derive(merged.stableId, merged.displayName, chatName, names, sameNameOther) },
            newId = newId,
        )
        log.log(
            "sr.profile_captured",
            mapOf(
                "fields" to saved.profile.fieldCount.toString(),
                "snapshots" to merged.snapshots.toString(),
                "key" to saved.profile.matchKey.kind.name,
                "ambiguous" to saved.profile.matchKey.ambiguous.toString(),
                "new" to saved.created.toString(),
            ),
        )
        if (saved.changed) onCaptured(saved.profile)
    }

    companion object {
        /** No snapshot for this long while on a profile: the viewing is over. */
        const val IDLE_FINISH_MS = 15_000L

        /** A gap this long between two snapshots always starts a new viewing. */
        const val STALE_MS = 120_000L

        /** A chat screen counts as "the same viewing" within this time of the profile. */
        const val CHAT_CONTEXT_MS = 600_000L
    }
}

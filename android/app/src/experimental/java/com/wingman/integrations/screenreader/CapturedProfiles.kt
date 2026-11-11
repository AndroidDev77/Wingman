package com.wingman.integrations.screenreader

import com.wingman.integrations.notifications.NotificationStateStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class UploadState {
    /** Saved on this phone, not uploaded yet. */
    LOCAL_ONLY,

    /** No paired connection for this app yet, so there is nowhere to upload to. */
    WAITING_FOR_CONNECTION,
    UPLOADED,
    FAILED,
}

/**
 * A profile captured from the screen, as stored on the device. Holds private text: [toString] is
 * redacted and nothing here is ever logged.
 */
@Serializable
data class CapturedProfile(
    val localId: String,
    val platform: String,
    val displayName: String?,
    val prompts: List<CapturedPrompt>,
    val basics: Map<String, CapturedField>,
    /** Shown on screen as short loose text; meaning unknown, so never uploaded as attributes. */
    val unlabeled: List<String> = emptyList(),
    val matchKey: MatchKey,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val needsUpload: Boolean = true,
    val uploadState: UploadState = UploadState.LOCAL_ONLY,
    /** Fixed, text-free explanation of [uploadState] for the list. */
    val uploadNote: String? = null,
    val serverProfileId: String? = null,
    val linkedConversationId: String? = null,
    val linkedConversationName: String? = null,
    val linkedBy: String? = null,
) {
    /** Name, prompts and labeled basics: what the list shows as "fields". */
    val fieldCount: Int get() = (if (displayName != null) 1 else 0) + prompts.size + basics.size

    /** The provider-side ID sent to the backend: the stable platform ID, else a device-made one. */
    val externalProfileId: String get() = matchKey.stableId?.takeIf { matchKey.kind == MatchKeyKind.STABLE_ID } ?: "screen:$localId"

    override fun toString(): String =
        "CapturedProfile(localId=$localId, platform=$platform, prompts=${prompts.size}, basics=${basics.keys.sorted()}, " +
            "key=$matchKey, upload=$uploadState, linked=${linkedConversationId != null})"
}

/** What saving a finished capture did. */
class CaptureSaveResult(val profile: CapturedProfile, val created: Boolean, val changed: Boolean)

/**
 * The on-device list of captured profiles, in the screen reader's app-private state file.
 *
 * Re-capturing someone: a capture is merged into an existing profile only on the same stable ID, or
 * on the same name AND at least one identical prompt/answer. The same name with different content
 * is a different person: a new entry, and both are marked ambiguous (never merged by name alone).
 */
class CapturedProfileStore(private val store: NotificationStateStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun all(): List<CapturedProfile> = store.keys(PREFIX).mapNotNull { store.get(it) }
        .mapNotNull { runCatching { json.decodeFromString(CapturedProfile.serializer(), it) }.getOrNull() }
        .sortedByDescending { it.lastSeenMillis }

    @Synchronized
    fun get(localId: String): CapturedProfile? =
        store.get(PREFIX + localId)?.let { runCatching { json.decodeFromString(CapturedProfile.serializer(), it) }.getOrNull() }

    @Synchronized
    fun save(p: CapturedProfile) = store.put(PREFIX + p.localId, json.encodeToString(CapturedProfile.serializer(), p))

    /** Applies [change] to the stored profile, if it still exists. */
    @Synchronized
    fun update(localId: String, change: (CapturedProfile) -> CapturedProfile): CapturedProfile? {
        val current = get(localId) ?: return null
        return change(current).also { save(it) }
    }

    @Synchronized
    fun remove(localId: String) = store.remove(PREFIX + localId)

    @Synchronized
    fun saveCapture(m: MergedProfile, platform: String, key: (sameNameOtherProfile: Boolean) -> MatchKey, newId: () -> String): CaptureSaveResult {
        val existing = all().filter { it.platform == platform }
        val sameName = existing.filter { a -> a.displayName != null && m.displayName != null && norm(a.displayName) == norm(m.displayName) }
        val target = existing.firstOrNull { m.stableId != null && it.matchKey.stableId == m.stableId }
            ?: sameName.firstOrNull { sharesPrompt(it, m) }
        val others = sameName.filter { it.localId != target?.localId }
        val matchKey = key(others.isNotEmpty())
        if (others.isNotEmpty()) {
            // The same name on two different profiles: neither name is an identity any more.
            others.filter { !it.matchKey.ambiguous && it.matchKey.kind != MatchKeyKind.STABLE_ID }
                .forEach { save(it.copy(matchKey = it.matchKey.copy(ambiguous = true))) }
        }
        if (target == null) {
            val created = CapturedProfile(
                localId = newId(), platform = platform, displayName = m.displayName, prompts = m.prompts, basics = m.basics,
                unlabeled = m.unlabeled, matchKey = matchKey, firstSeenMillis = m.firstSeenMillis, lastSeenMillis = m.lastSeenMillis,
            )
            save(created)
            return CaptureSaveResult(created, created = true, changed = true)
        }
        val prompts = LinkedHashMap<String, CapturedPrompt>()
        target.prompts.forEach { prompts[norm(it.question)] = it }
        for (p in m.prompts) {
            val key = norm(p.question)
            val had = prompts[key]
            // What the screen shows now wins, unless it is only the cut-off beginning of what is stored.
            if (had == null || !had.answer.startsWith(p.answer.trimEnd('…', '.', ' ')) || p.answer.length >= had.answer.length) prompts[key] = p
        }
        val merged = target.copy(
            displayName = m.displayName ?: target.displayName,
            prompts = prompts.values.toList(),
            basics = target.basics + m.basics,
            unlabeled = (target.unlabeled + m.unlabeled).distinct().take(ProfileExtractor.MAX_UNLABELED),
            // A stable ID once seen is kept; otherwise the newer key.
            matchKey = if (target.matchKey.kind == MatchKeyKind.STABLE_ID) target.matchKey else matchKey,
            lastSeenMillis = maxOf(target.lastSeenMillis, m.lastSeenMillis),
        )
        val changed = merged.prompts != target.prompts || merged.basics != target.basics || merged.displayName != target.displayName
        val out = if (changed) merged.copy(needsUpload = true) else merged
        save(out)
        return CaptureSaveResult(out, created = false, changed = changed)
    }

    /** Server profile IDs whose deletion on the backend has not succeeded yet. */
    @Synchronized
    fun pendingDeletes(): List<String> = store.keys(DELETE_PREFIX).map { it.removePrefix(DELETE_PREFIX) }

    @Synchronized
    fun addPendingDelete(serverProfileId: String) = store.put(DELETE_PREFIX + serverProfileId, "1")

    @Synchronized
    fun removePendingDelete(serverProfileId: String) = store.remove(DELETE_PREFIX + serverProfileId)

    private fun norm(s: String) = ScreenProfile.normalize(s)

    private fun sharesPrompt(a: CapturedProfile, m: MergedProfile): Boolean {
        val seen = a.prompts.map { norm(it.question) + "\u0000" + norm(it.answer) }.toSet()
        return m.prompts.any { norm(it.question) + "\u0000" + norm(it.answer) in seen }
    }

    companion object {
        const val PREFIX = "sr:profile:"
        const val DELETE_PREFIX = "sr:delete:"
    }
}

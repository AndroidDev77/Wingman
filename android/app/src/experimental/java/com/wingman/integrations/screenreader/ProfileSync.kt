package com.wingman.integrations.screenreader

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.ConversationFilter
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ConversationProfileLinkRequest
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfileLinkOrigin
import com.wingman.core.model.ProfilePrompt
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ProfileSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/** What deleting a captured profile did. */
enum class DeleteOutcome {
    /** Gone from this phone and from the account (or it was never uploaded). */
    DELETED,

    /** Gone from this phone; the account copy will be deleted as soon as the server is reachable. */
    DELETED_LOCALLY_SERVER_PENDING,
    NOT_FOUND,
}

/**
 * Uploads captured profiles through `importProfile` (`source: device`), links them to a conversation
 * and deletes them. Links: automatically ONLY through [MatchLinker] (a stable ID that equals one
 * conversation's thread ID, sent as `device_match`, which the server checks again); otherwise the
 * user picks the conversation and the link is sent as `user`.
 *
 * Never logs profile content: [log] gets states and counts.
 *
 * @param connectionId the paired connection of a platform whose `profile_read` the server knows
 *   about (the app's notification connection), or null when there is none yet.
 */
class ProfileSync(
    private val api: WingmanApi,
    private val store: CapturedProfileStore,
    private val connectionId: (platform: String) -> String?,
    private val log: ScreenReaderLog = ScreenReaderLog.NONE,
    private val newKey: () -> IdempotencyKey = IdempotencyKey.Companion::random,
) {
    @Volatile private var conversationCache: List<ConversationIdentity> = emptyList()

    /** Contact names of [platform]'s conversations as last loaded (for the match key). */
    fun knownConversationNames(platform: String): List<String> = conversationCache.filter { it.platform == platform }.map { it.contactName }

    /** Retries pending account deletions, then uploads whatever still needs it. */
    suspend fun syncAll() {
        for (id in store.pendingDeletes()) deleteOnServer(id)
        for (p in store.all()) if (p.needsUpload) upload(p.localId)
    }

    /** Uploads one profile and, when it is unlinked, tries the automatic (stable ID) link. */
    suspend fun upload(localId: String): CapturedProfile? {
        val p = store.get(localId) ?: return null
        val provider = providerOf(p.platform) ?: return store.update(localId) { it.copy(uploadState = UploadState.FAILED, uploadNote = NOTE_UNSUPPORTED_APP) }
        val connection = connectionId(p.platform)
            ?: return store.update(localId) { it.copy(uploadState = UploadState.WAITING_FOR_CONNECTION, uploadNote = NOTE_NO_CONNECTION) }
        val body = runCatching { importRequest(p, provider, connection) }.getOrNull()
            ?: return store.update(localId) { it.copy(uploadState = UploadState.FAILED, uploadNote = NOTE_INVALID) }
        return when (val r = api.importProfile(body, newKey())) {
            is ApiResult.Success -> {
                log.log("sr.upload", mapOf("state" to "uploaded", "fields" to p.fieldCount.toString()))
                val saved = store.update(localId) {
                    it.copy(needsUpload = false, uploadState = UploadState.UPLOADED, uploadNote = null, serverProfileId = r.value.id)
                }
                if (saved != null && saved.linkedConversationId == null) autoLink(saved) else saved
            }
            is ApiResult.Failure -> {
                val note = if (r.error.code == ErrorCode.VALIDATION_ERROR) NOTE_REFUSED else NOTE_RETRY
                log.log("sr.upload", mapOf("state" to "failed", "error" to errorClass(r.error)))
                store.update(localId) { it.copy(uploadState = UploadState.FAILED, uploadNote = note) }
            }
        }
    }

    /** The conversations the user can pick from, or the automatic link when one is certain. */
    suspend fun linkDecision(localId: String): LinkDecision {
        val p = store.get(localId) ?: return LinkDecision.Ask(emptyList())
        return MatchLinker.decide(p.matchKey, p.platform, loadConversations(p.platform))
    }

    /** The user's own choice in the "Which match is this?" picker. */
    suspend fun linkByUser(localId: String, conversation: ConversationIdentity): Boolean =
        link(localId, conversation.conversationId, conversation.contactName, ProfileLinkOrigin.USER)

    suspend fun unlink(localId: String): Boolean {
        val p = store.get(localId) ?: return false
        val conversation = p.linkedConversationId ?: return true
        val r = api.deleteConversationProfileLink(conversation)
        val gone = r is ApiResult.Success || r.errorOrNull()?.code == ErrorCode.NOT_FOUND
        if (gone) store.update(localId) { it.copy(linkedConversationId = null, linkedConversationName = null, linkedBy = null) }
        return gone
    }

    /**
     * Deletes the profile from this phone first, then from the account. If the server cannot be
     * reached the account deletion is remembered and retried by [syncAll].
     */
    suspend fun delete(localId: String): DeleteOutcome {
        val p = store.get(localId) ?: return DeleteOutcome.NOT_FOUND
        store.remove(localId)
        val serverId = p.serverProfileId ?: return DeleteOutcome.DELETED
        store.addPendingDelete(serverId)
        return if (deleteOnServer(serverId)) DeleteOutcome.DELETED else DeleteOutcome.DELETED_LOCALLY_SERVER_PENDING
    }

    private suspend fun deleteOnServer(serverProfileId: String): Boolean {
        val r = api.deleteProfile(serverProfileId)
        val gone = r is ApiResult.Success || r.errorOrNull()?.code == ErrorCode.NOT_FOUND
        if (gone) store.removePendingDelete(serverProfileId)
        log.log("sr.delete", mapOf("state" to if (gone) "deleted" else "pending"))
        return gone
    }

    private suspend fun autoLink(p: CapturedProfile): CapturedProfile? {
        val decision = MatchLinker.decide(p.matchKey, p.platform, loadConversations(p.platform))
        if (decision !is LinkDecision.Auto) return p
        val name = conversationCache.firstOrNull { it.conversationId == decision.conversationId }?.contactName
        link(p.localId, decision.conversationId, name, ProfileLinkOrigin.DEVICE_MATCH)
        return store.get(p.localId)
    }

    private suspend fun link(localId: String, conversationId: String, name: String?, origin: ProfileLinkOrigin): Boolean {
        var p = store.get(localId) ?: return false
        if (p.serverProfileId == null || p.needsUpload) p = upload(localId) ?: return false
        val serverId = p.serverProfileId ?: return false
        val r = api.putConversationProfileLink(conversationId, ConversationProfileLinkRequest(serverId, origin))
        val ok = r is ApiResult.Success
        log.log("sr.link", mapOf("origin" to origin.name.lowercase(), "ok" to ok.toString()))
        if (ok) {
            store.update(localId) {
                it.copy(linkedConversationId = conversationId, linkedConversationName = name, linkedBy = if (origin == ProfileLinkOrigin.USER) "you" else "matching ID")
            }
        }
        return ok
    }

    private suspend fun loadConversations(platform: String): List<ConversationIdentity> {
        val out = ArrayList<ConversationIdentity>()
        var cursor: String? = null
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val r = api.listConversations(PageRequest(cursor = cursor, limit = 100), ConversationFilter(platform = platform))
            val value = (r as? ApiResult.Success)?.value ?: return out // not cached: an incomplete list
            value.items.forEach { out += ConversationIdentity(it.id, it.platform, it.externalThreadId, it.contact.displayName) }
            cursor = value.nextCursor ?: break
        }
        conversationCache = conversationCache.filter { it.platform != platform } + out
        return out
    }

    private fun errorClass(e: ApiError): String = when (e) {
        is ApiError.Http -> "http_${e.status}"
        is ApiError.Network -> "network"
        is ApiError.Decoding -> "decoding"
        is ApiError.Client -> "client"
    }

    companion object {
        const val MAX_PAGES = 5
        const val NOTE_NO_CONNECTION = "Saved on this phone. To upload it, turn on Hinge under Settings > Dating apps (notifications) so Wingman has a Hinge connection."
        const val NOTE_REFUSED = "The server refused this profile. Open Wingman once so it reports that profile reading is on, then tap Upload again."
        const val NOTE_RETRY = "Upload failed. It will be tried again; you can also tap Upload again."
        const val NOTE_INVALID = "This capture can't be uploaded as it is."
        const val NOTE_UNSUPPORTED_APP = "Upload isn't available for this app."

        fun providerOf(platform: String): ProfileProvider? = when (platform) {
            "hinge" -> ProfileProvider.HINGE
            else -> null
        }

        /**
         * The contract request for a captured profile: display name, prompts and labeled basics only,
         * cut to the contract's limits. Unlabeled texts are not sent. Nothing is added or inferred.
         */
        fun importRequest(p: CapturedProfile, provider: ProfileProvider, connectionId: String): ProfileImportRequest {
            val attributes = LinkedHashMap<String, JsonElement>()
            for ((name, field) in p.basics) {
                if (ProfileImportRequest.ATTRIBUTE_NAME.matches(name) && field.value.isNotBlank()) attributes[name] = JsonPrimitive(field.value.take(500))
            }
            return ProfileImportRequest(
                provider = provider,
                externalProfileId = p.externalProfileId.take(256),
                source = ProfileSource.DEVICE,
                connectionId = connectionId,
                displayName = p.displayName?.take(120),
                prompts = p.prompts.filter { it.question.isNotBlank() }.take(20).map { ProfilePrompt(it.question.take(300), it.answer.take(1000)) },
                attributes = attributes,
                sourceObservedAt = Instant.ofEpochMilli(p.lastSeenMillis),
            )
        }
    }
}

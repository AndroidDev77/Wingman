package com.wingman.integrations.screenreader

import kotlinx.serialization.Serializable

/** What identifies the person a captured profile belongs to. */
@Serializable
enum class MatchKeyKind {
    /** A stable platform ID read from the screen: the only kind that can link by itself. */
    STABLE_ID,

    /** A display name, and the chat screen of the same viewing showed the same name. */
    NAME_AND_CHAT,

    /** A display name only. */
    NAME_ONLY,

    /** Nothing usable. */
    NONE,
}

/**
 * The match key of a captured profile (ADR 0008 section 4 identity rules): a stable ID is an
 * identity, a label is not. [ambiguous] is true whenever two matches could collide: always for a
 * name without chat context, and for a name that occurs more than once among the names Wingman knows.
 */
@Serializable
data class MatchKey(
    val kind: MatchKeyKind,
    val stableId: String? = null,
    val displayName: String? = null,
    val ambiguous: Boolean,
) {
    override fun toString(): String = "MatchKey(kind=$kind, ambiguous=$ambiguous)"
}

object MatchKeys {
    /**
     * @param chatHeaderName the name in the header of the chat screen seen in the same viewing, if any
     * @param nameSources lists of names Wingman knows on this platform, one list per source (the
     *   match-list rows last seen, the conversations' contacts). A name that occurs twice within
     *   one source belongs to two matches.
     * @param sameNameOtherProfile another captured profile has this name but different content
     */
    fun derive(
        stableId: String?,
        displayName: String?,
        chatHeaderName: String?,
        nameSources: List<Collection<String>> = emptyList(),
        sameNameOtherProfile: Boolean = false,
    ): MatchKey {
        if (!stableId.isNullOrBlank()) return MatchKey(MatchKeyKind.STABLE_ID, stableId, displayName, ambiguous = false)
        if (displayName.isNullOrBlank()) return MatchKey(MatchKeyKind.NONE, null, null, ambiguous = true)
        val norm = ScreenProfile.normalize(displayName)
        val collides = sameNameOtherProfile || nameSources.any { source -> source.count { ScreenProfile.normalize(it) == norm } >= 2 }
        val chat = chatHeaderName != null && ScreenProfile.normalize(chatHeaderName) == norm
        return if (chat) {
            MatchKey(MatchKeyKind.NAME_AND_CHAT, null, displayName, ambiguous = collides)
        } else {
            // A name with no chat context is never an identity.
            MatchKey(MatchKeyKind.NAME_ONLY, null, displayName, ambiguous = true)
        }
    }
}

/** A conversation as far as linking is concerned. */
data class ConversationIdentity(
    val conversationId: String,
    val platform: String,
    val externalThreadId: String?,
    val contactName: String,
)

/** One row of the "Which match is this?" picker. */
data class LinkCandidate(val conversation: ConversationIdentity, val sameName: Boolean)

sealed interface LinkDecision {
    /** The profile's stable ID is exactly one conversation's stable thread ID. */
    data class Auto(val conversationId: String) : LinkDecision

    /** The user decides. [candidates] lists same-name conversations first; it may be empty. */
    data class Ask(val candidates: List<LinkCandidate>) : LinkDecision
}

/**
 * Automatic linking happens ONLY for an unambiguous [MatchKeyKind.STABLE_ID] key that equals exactly
 * one conversation's `external_thread_id` on the same platform. A name (with or without chat
 * context) never links by itself: it only orders the picker.
 */
object MatchLinker {
    fun decide(key: MatchKey, platform: String, conversations: List<ConversationIdentity>): LinkDecision {
        val onPlatform = conversations.filter { it.platform == platform }
        if (key.kind == MatchKeyKind.STABLE_ID && !key.ambiguous && key.stableId != null) {
            val hits = onPlatform.filter { it.externalThreadId != null && it.externalThreadId == key.stableId }
            if (hits.size == 1) return LinkDecision.Auto(hits.single().conversationId)
        }
        val norm = key.displayName?.let { ScreenProfile.normalize(it) }
        val candidates = onPlatform.map { LinkCandidate(it, norm != null && ScreenProfile.normalize(it.contactName) == norm) }
        return LinkDecision.Ask(candidates.sortedByDescending { it.sameName })
    }
}

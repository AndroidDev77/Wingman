package com.wingman.features.approvals

import com.wingman.core.api.ApiResult
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.Conversation

/**
 * Conversations by ID, used to show who an approval or audit entry is about. Approvals and audit
 * entries carry only IDs (contract `Approval`, `AgentAction`), so the screens join them to the
 * conversation list. Best effort: a failure gives null and rows fall back to generic labels.
 */
data class ConversationIndex(val byId: Map<String, Conversation>, val complete: Boolean) {
    fun name(conversationId: String?): String? = conversationId?.let { byId[it]?.contact?.displayName }

    fun nameForContact(contactId: String?): String? =
        contactId?.let { id -> byId.values.firstOrNull { it.contact.id == id }?.contact?.displayName }

    /** One row per contact (a contact may have several conversations; the most recent wins). */
    fun contacts(): List<Conversation> =
        byId.values.sortedByDescending { it.lastActivityAt }.distinctBy { it.contact.id }

    companion object {
        val EMPTY = ConversationIndex(emptyMap(), complete = false)

        /** Follows `next_cursor` for up to [maxPages] pages of 100. */
        suspend fun load(api: WingmanApi, maxPages: Int = 10): ApiResult<ConversationIndex> {
            val all = LinkedHashMap<String, Conversation>()
            var cursor: String? = null
            for (i in 0 until maxPages) {
                when (val r = api.listConversations(PageRequest(cursor, 100))) {
                    is ApiResult.Failure -> return r
                    is ApiResult.Success -> {
                        r.value.items.forEach { all[it.id] = it }
                        cursor = r.value.nextCursor
                    }
                }
                if (cursor == null) return ApiResult.Success(ConversationIndex(all, complete = true))
            }
            return ApiResult.Success(ConversationIndex(all, complete = false))
        }
    }
}

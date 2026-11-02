package com.wingman.integrations.notifications

/**
 * DEBUG-ONLY notification inspector. Produces a REDACTED structural description of one
 * notification, so a tester can capture evidence for `docs/integrations/notification_matrix.md`
 * (which fields an app fills, whether it has a RemoteInput reply action) without capturing any
 * message content.
 *
 * What it emits: field names with presence and length, MessagingStyle message count and distinct
 * sender count (never names), shortcut/group presence, summary flag, category, channel ID,
 * template, action titles (scrubbed: any word that also appears in a title, text or sender name is
 * replaced by `<w>`, digits by `#`, max 32 chars), RemoteInput presence, result keys and
 * free-form flags, semantic action, signing-certificate digests (public data).
 *
 * What it never emits: titles, texts, message bodies, sender names, conversation titles,
 * shortcut IDs or notification keys in clear.
 *
 * The Android service calls it only on a debuggable build, only while the tester has switched it
 * on, and only for [targets]: the plugin allowlist plus [CANDIDATE_PACKAGES].
 */
object NotificationInspector {
    /**
     * Package names to inspect before any plugin exists. Taken from the public Play Store listings;
     * UNVERIFIED until the matrix device run confirms them (the inspector output shows the package).
     */
    val CANDIDATE_PACKAGES: Map<String, String> = mapOf(
        "com.tinder" to "tinder",
        "com.bumble.app" to "bumble",
        "co.hinge.app" to "hinge",
    )

    fun targets(registry: NotificationPluginRegistry): Set<String> = registry.allowlist + CANDIDATE_PACKAGES.keys

    fun describe(source: NotificationSource, s: NotificationSnapshot): Map<String, String> {
        val private = privateWords(s)
        val out = LinkedHashMap<String, String>()
        out["pkg"] = s.packageName
        out["key_hash"] = NotificationHashes.short(s.key)
        out["post_time"] = s.postTimeMillis.toString()
        out["when_set"] = (s.whenMillis > 0).toString()
        out["title"] = presence(s.title)
        out["text"] = presence(s.text)
        out["big_text"] = presence(s.bigText)
        out["sub_text"] = presence(s.subText)
        out["conversation_title"] = presence(s.conversationTitle)
        out["text_ends_with_ellipsis"] = NotificationParsing.looksTruncated(s.text ?: s.bigText).toString()
        out["is_group_conversation"] = s.isGroupConversation.toString()
        out["messages"] = s.messages.size.toString()
        out["message_senders_distinct"] = s.messages.map { it.senderName }.distinct().size.toString()
        out["message_sender_keys"] = s.messages.count { it.senderKey != null }.toString()
        out["message_own"] = s.messages.count { it.senderName == null }.toString()
        out["message_with_data"] = s.messages.count { it.hasData }.toString()
        out["shortcut_id"] = if (s.shortcutId == null) "absent" else "present#" + NotificationHashes.short(s.shortcutId)
        out["group"] = if (s.groupKey == null) "absent" else "present"
        out["group_summary"] = s.isGroupSummary.toString()
        out["category"] = s.category ?: "null"
        out["channel_id"] = scrub(s.channelId, private, 48)
        out["template"] = s.template?.substringAfterLast('$')?.substringAfterLast('.') ?: "null"
        out["public_version"] = s.hasPublicVersion.toString()
        out["actions"] = s.actions.size.toString()
        s.actions.forEach { a ->
            val p = "action${a.index}"
            out["$p.title"] = scrub(a.title, private, 32)
            out["$p.remote_inputs"] = a.remoteInputs.size.toString()
            out["$p.free_form"] = a.hasFreeFormRemoteInput.toString()
            out["$p.result_keys"] = a.remoteInputs.joinToString("|") { scrub(it.resultKey, private, 48) }
            out["$p.semantic"] = a.semanticAction.toString()
            out["$p.contextual"] = a.isContextual.toString()
        }
        out["reply_action_candidate"] = (s.defaultReplyAction()?.index?.toString() ?: "none")
        out["cert_sha256"] = source.signingCertSha256?.sorted()?.joinToString("|") ?: "unavailable"
        return out
    }

    /** One log line: `k=v` pairs. Values are already redacted. */
    fun line(fields: Map<String, String>): String = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }

    private fun presence(v: String?): String = when {
        v == null -> "null"
        v.isEmpty() -> "empty"
        else -> "len${v.length}"
    }

    private val WORD = Regex("[\\p{L}\\p{N}']+")

    private fun privateWords(s: NotificationSnapshot): Set<String> =
        (listOf(s.title, s.text, s.bigText, s.subText, s.conversationTitle) + s.messages.flatMap { listOf(it.text, it.senderName) })
            .filterNotNull()
            .flatMap { WORD.findAll(it).map { m -> m.value.lowercase() }.toList() }
            .filter { it.length >= 2 }
            .toSet()

    /** Replaces private words with `<w>` and digits with `#`, then caps the length. */
    internal fun scrub(v: String?, privateWords: Set<String>, max: Int): String {
        if (v == null) return "null"
        val replaced = WORD.replace(v) { m -> if (m.value.lowercase() in privateWords) "<w>" else m.value }
            .replace(Regex("[0-9]"), "#")
            .replace(Regex("\\s+"), "_")
        return if (replaced.length > max) replaced.take(max) + "~" else replaced
    }
}

/**
 * Plain-language text for the notification-access explainer (shown by `NotificationAccessActivity`
 * before the user is sent to the system settings). Kept here so it is unit-testable and reusable by
 * a Compose screen later.
 */
object NotificationAccessExplainer {
    const val TITLE = "Let Wingman read dating-app notifications"

    val BODY: List<String> = listOf(
        "Android will ask you to give Wingman \"notification access\". With it, Wingman can see the notifications on this phone.",
        "Wingman only looks at notifications from the dating apps you turn on in Wingman. Every other app's notifications are ignored and never stored.",
        "From those apps it reads what the notification shows: the sender's name as shown, the message preview and its time. Previews are often shortened or hidden; Wingman only knows what the notification shows.",
        "Wingman never taps, swipes or opens anything in other apps. If a notification has a reply box, Wingman can use it only after you turn on direct reply for that app, and only for a reply you approved.",
        "A reply through a notification is marked \"handed to the app\", never \"sent\" or \"delivered\": Wingman cannot see what the app does with it.",
        "Message text is kept only on this phone until it is uploaded to your Wingman account, and it is never written to logs.",
        "To stop at any time: Settings > Notifications > Device & app notifications (or Notification access) > Wingman > off. Wingman stops reading at once.",
    )
}

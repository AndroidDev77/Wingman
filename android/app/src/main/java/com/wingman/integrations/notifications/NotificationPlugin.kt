package com.wingman.integrations.notifications

/**
 * One per-app plugin: the ONLY way a package enters the notification allowlist. WING-027 (Tinder),
 * WING-028 (Bumble) and WING-029 (Hinge) each provide one; this ticket ships none, so the
 * allowlist is empty by default and the listener ignores every notification.
 *
 * Contract for implementations:
 * - [packageNames] are exact package names. Matching is exact, never by prefix or similarity.
 * - [isAuthentic] decides whether a notification really comes from the app. The default
 *   ([NotificationAuthenticity.check]) requires an exact package match and, when the adapter
 *   could read the signing certificates and the plugin pins digests, a matching digest.
 * - [parse] is pure and total: it never throws for odd input, returns an empty list for a
 *   notification it does not understand, and keeps unknown shapes as [NotificationEventKind.UNKNOWN].
 *   It must not log.
 * - [replyAction] picks the action a reply would go through, or null. Overriding it is how a
 *   plugin restricts replies (e.g. to MessagingStyle chat notifications only).
 */
interface NotificationAppPlugin {
    /** Platform key, lowercase snake_case: `tinder`, `bumble`, `hinge`. */
    val platform: String

    /** Exact package names this plugin accepts. Non-empty. */
    val packageNames: Set<String>

    /**
     * Lowercase hex SHA-256 digests of the app's genuine signing certificates. Empty means "not
     * pinned yet" (the digests are captured with the debug inspector, docs/integrations/notification_matrix.md).
     */
    val trustedSigningCertSha256: Set<String>
        get() = emptySet()

    fun isAuthentic(source: NotificationSource, snapshot: NotificationSnapshot): AuthenticityVerdict =
        NotificationAuthenticity.check(this, source, snapshot)

    fun parse(snapshot: NotificationSnapshot): List<ParsedNotificationEvent>

    fun replyAction(snapshot: NotificationSnapshot): SnapshotAction? = snapshot.defaultReplyAction()
}

/** Result of [NotificationAppPlugin.isAuthentic]. Reasons are fixed strings, safe to log. */
sealed interface AuthenticityVerdict {
    /** [certificatePinned] is true only when a pinned digest matched. */
    data class Authentic(val certificatePinned: Boolean) : AuthenticityVerdict

    data class Rejected(val reason: String) : AuthenticityVerdict
}

object NotificationAuthenticity {
    const val REASON_PACKAGE_MISMATCH = "package_not_owned_by_plugin"
    const val REASON_SNAPSHOT_SOURCE_MISMATCH = "snapshot_package_differs_from_source"
    const val REASON_CERT_MISMATCH = "signing_certificate_mismatch"
    const val REASON_NO_CERT_EVIDENCE = "signing_certificate_unavailable"

    /**
     * Default check.
     * 1. The posting package must be EXACTLY one of the plugin's packages (no prefix, case or
     *    lookalike match: `com.tinder.fake`, `com.tinderr`, `Com.Tinder` are all rejected).
     * 2. The snapshot must name the same package as the source.
     * 3. If the plugin pins digests: the source's digests, when readable, must intersect them.
     *    When pinned but unreadable, [requireCertWhenPinned] decides (default: reject).
     * 4. Not pinned: accepted on the package match alone, with `certificatePinned = false`.
     */
    fun check(
        plugin: NotificationAppPlugin,
        source: NotificationSource,
        snapshot: NotificationSnapshot,
        requireCertWhenPinned: Boolean = true,
    ): AuthenticityVerdict {
        if (source.packageName !in plugin.packageNames) return AuthenticityVerdict.Rejected(REASON_PACKAGE_MISMATCH)
        if (snapshot.packageName != source.packageName) return AuthenticityVerdict.Rejected(REASON_SNAPSHOT_SOURCE_MISMATCH)
        val pinned = plugin.trustedSigningCertSha256.map { it.lowercase() }.toSet()
        if (pinned.isEmpty()) return AuthenticityVerdict.Authentic(certificatePinned = false)
        val observed = source.signingCertSha256?.map { it.lowercase() }?.toSet()
        if (observed == null) {
            return if (requireCertWhenPinned) AuthenticityVerdict.Rejected(REASON_NO_CERT_EVIDENCE) else AuthenticityVerdict.Authentic(false)
        }
        return if (observed.any { it in pinned }) AuthenticityVerdict.Authentic(true) else AuthenticityVerdict.Rejected(REASON_CERT_MISMATCH)
    }
}

/**
 * The set of registered plugins. Its packages are the allowlist: a package no plugin owns is
 * ignored before anything is extracted from its notification. Two plugins claiming one package,
 * or one platform twice, is a programming error and fails at construction.
 */
class NotificationPluginRegistry(plugins: List<NotificationAppPlugin> = emptyList()) {
    private val byPackage: Map<String, NotificationAppPlugin>
    private val byPlatform: Map<String, NotificationAppPlugin>

    init {
        val pk = LinkedHashMap<String, NotificationAppPlugin>()
        val pf = LinkedHashMap<String, NotificationAppPlugin>()
        for (p in plugins) {
            require(PLATFORM_PATTERN.matches(p.platform)) { "platform key must match ${PLATFORM_PATTERN.pattern}" }
            require(p.packageNames.isNotEmpty()) { "plugin ${p.platform} has no packages" }
            require(pf.put(p.platform, p) == null) { "platform ${p.platform} registered twice" }
            for (name in p.packageNames) {
                require(name.isNotBlank() && name == name.trim()) { "blank package name in ${p.platform}" }
                require(pk.put(name, p) == null) { "package $name claimed by two plugins" }
            }
        }
        byPackage = pk
        byPlatform = pf
    }

    /** All allowlisted package names. Empty by default. */
    val allowlist: Set<String> get() = byPackage.keys

    val platforms: Set<String> get() = byPlatform.keys

    fun forPackage(packageName: String): NotificationAppPlugin? = byPackage[packageName]

    fun forPlatform(platform: String): NotificationAppPlugin? = byPlatform[platform]

    companion object {
        val PLATFORM_PATTERN = Regex("^[a-z][a-z0-9_]{1,31}$")
    }
}

/** Small helpers for plugin authors. Pure and side-effect free. */
object NotificationParsing {
    private val ELLIPSIS = Regex("(…|\\.\\.\\.)\\s*$")

    /** True when [text] ends like an app-shortened preview ("…" or "..."). */
    fun looksTruncated(text: String?): Boolean = text != null && ELLIPSIS.containsMatchIn(text)

    /** Best source timestamp: message time, else `when`, else post time. */
    fun occurredAt(snapshot: NotificationSnapshot, message: SnapshotMessage? = null): Long = when {
        message != null && message.timestampMillis > 0 -> message.timestampMillis
        snapshot.whenMillis > 0 -> snapshot.whenMillis
        else -> snapshot.postTimeMillis
    }

    /** Messages from other people (MessagingStyle sender null = the device user's own message). */
    fun incomingMessages(snapshot: NotificationSnapshot): List<SnapshotMessage> = snapshot.messages.filter { it.senderName != null }
}

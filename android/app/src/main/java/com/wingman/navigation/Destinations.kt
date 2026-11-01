package com.wingman.navigation

/*
 * Route table for the app shell. Pure Kotlin (no androidx imports) so routes, argument
 * validation and the screen registry are JVM-testable. The Compose NavHost lives in WingmanNavHost.kt.
 */

/** Top-level destinations, plus the Conversation detail destination. */
enum class Destination(
    /** Route pattern given to Compose Navigation. */
    val pattern: String,
    val label: String,
    /** Shown in the bottom navigation bar. */
    val topLevel: Boolean,
) {
    Dashboard("dashboard", "Home", topLevel = true),
    Inbox("inbox", "Inbox", topLevel = true),
    Approvals("approvals", "Approvals", topLevel = true),
    Discovery("discovery", "Discovery", topLevel = true),
    Settings("settings", "Settings", topLevel = true),
    Conversation("conversation/{${Routes.ARG_CONVERSATION_ID}}", "Conversation", topLevel = false),
    ;

    companion object {
        val START = Dashboard
        val topLevelDestinations: List<Destination> = entries.filter { it.topLevel }

        /** Resolves a concrete or pattern route (e.g. `conversation/<id>`) back to its destination. */
        fun fromRoute(route: String?): Destination? {
            if (route == null) return null
            val head = route.substringBefore('/').substringBefore('?')
            return entries.firstOrNull { it.pattern.substringBefore('/') == head }
        }
    }
}

/** Builders for concrete routes. Always use these; never concatenate route strings in features. */
object Routes {
    const val ARG_CONVERSATION_ID = "conversationId"

    private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** True for a contract `Id` (lowercase canonical UUID, ADR 0001). */
    fun isValidId(id: String?): Boolean = id != null && UUID_PATTERN.matches(id)

    fun dashboard() = Destination.Dashboard.pattern
    fun inbox() = Destination.Inbox.pattern
    fun approvals() = Destination.Approvals.pattern
    fun discovery() = Destination.Discovery.pattern
    fun settings() = Destination.Settings.pattern

    /** `conversation/<id>`. Rejects non-contract IDs so a malformed deep link can't reach the screen. */
    fun conversation(conversationId: String): String {
        require(isValidId(conversationId)) { "conversationId must be a lowercase canonical UUID" }
        return "conversation/$conversationId"
    }
}

/** Arguments handed to a screen. Only Conversation has one today. */
data class ScreenArgs(val conversationId: String? = null) {
    companion object {
        val NONE = ScreenArgs()
    }
}

/** Navigation actions available to screens, decoupled from NavController for testability. */
interface Navigator {
    fun navigateTo(destination: Destination)
    fun openConversation(conversationId: String)
    fun back()
}

/**
 * Lets feature tickets plug their screens into the shell WITHOUT editing the nav graph.
 * [C] is the screen content type; on Android it is [ScreenContent] (a composable lambda).
 *
 * Each destination has at most one registration; registering twice is a programming error so
 * two features can't silently fight over a route. Unregistered destinations render the shell's
 * placeholder screen.
 */
class ScreenRegistry<C : Any> {
    private val entries = LinkedHashMap<Destination, C>()

    fun register(destination: Destination, content: C): ScreenRegistry<C> {
        check(destination !in entries) { "A screen is already registered for $destination" }
        entries[destination] = content
        return this
    }

    fun resolve(destination: Destination): C? = entries[destination]

    fun registered(): Set<Destination> = entries.keys.toSet()
}

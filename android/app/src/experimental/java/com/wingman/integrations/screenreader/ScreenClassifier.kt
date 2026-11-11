package com.wingman.integrations.screenreader

import kotlinx.serialization.Serializable

/** What kind of screen a snapshot shows. */
enum class ScreenKind { PROFILE, CHAT, MATCH_LIST, OTHER }

/** How a classification or a field was arrived at. Declared strongest first. */
@Serializable
enum class EvidenceSource {
    /** A view id listed in the app's [ScreenProfile] (filled in after an inspector run). */
    ID_HINT,

    /** The text equals one of the app's public prompt questions. */
    KNOWN_PROMPT,

    /** Layout only: a heading above a body, a text input at the bottom, rows in a list. */
    STRUCTURE,

    /** The text's own form (for example a height such as `170 cm`). */
    SHAPE,
}

/**
 * Per-app definition of what to look for. View ids are matched as a fragment of the id's entry name
 * (the part after `:id/`), case-insensitively. Every id set may be empty: the classifier and the
 * extractor then fall back to structure. Real ids are filled in from the owner's inspector export.
 */
data class ScreenProfile(
    val packageName: String,
    val platform: String,
    val profileScreenIds: Set<String> = emptySet(),
    val chatScreenIds: Set<String> = emptySet(),
    val matchListIds: Set<String> = emptySet(),
    val nameIds: Set<String> = emptySet(),
    val promptQuestionIds: Set<String> = emptySet(),
    val promptAnswerIds: Set<String> = emptySet(),
    /** id fragment -> attribute name (lowercase snake_case, as the contract requires). */
    val basicsIds: Map<String, String> = emptyMap(),
    val chatHeaderNameIds: Set<String> = emptySet(),
    val messageTextIds: Set<String> = emptySet(),
    /** Ids of nodes whose text is a stable platform ID of the match. None known for any app. */
    val stableIdIds: Set<String> = emptySet(),
    val knownPromptQuestions: Set<String> = emptySet(),
    /** The app's own labels (tabs, buttons, status lines): never a name, a prompt or a message. */
    val chromeTexts: Set<String> = emptySet(),
) {
    private val known = knownPromptQuestions.map { normalize(it) }.toSet()
    private val chrome = chromeTexts.map { normalize(it) }.toSet()

    fun isKnownPrompt(text: String): Boolean = normalize(text) in known
    fun isChrome(text: String): Boolean = normalize(text) in chrome

    companion object {
        fun normalize(text: String): String = text.trim().trimEnd('.', ':', '…').lowercase().replace(Regex("\\s+"), " ")

        fun idMatches(viewId: String?, fragments: Collection<String>): Boolean {
            if (viewId == null || fragments.isEmpty()) return false
            val entry = viewId.substringAfterLast('/').lowercase()
            return fragments.any { entry.contains(it.lowercase()) }
        }

        /**
         * Best guess for Hinge, written WITHOUT having seen the app's tree (UNVERIFIED). It does not
         * depend on view ids: the id fragments below are generic words that cost nothing when they do
         * not occur. The prompt questions are Hinge's public prompt titles; a profile is recognized
         * by prompt/answer pairs (a short heading above a body) and a row of basics.
         */
        val HINGE = ScreenProfile(
            packageName = ScreenReaderApps.HINGE,
            platform = "hinge",
            nameIds = setOf("profile_name", "first_name", "subject_name"),
            promptQuestionIds = setOf("prompt_question", "prompt_title", "question"),
            promptAnswerIds = setOf("prompt_answer", "answer"),
            basicsIds = linkedMapOf(
                "job" to "job_title",
                "work" to "job_title",
                "school" to "school",
                "education" to "school",
                "hometown" to "hometown",
                "height" to "height",
                "location" to "location_label",
            ),
            chatHeaderNameIds = setOf("chat_title", "toolbar_title", "match_name"),
            messageTextIds = setOf("message_text", "chat_message", "bubble_text"),
            knownPromptQuestions = setOf(
                "Typical Sunday", "My simple pleasures", "I go crazy for", "Dating me is like", "Two truths and a lie",
                "The way to win me over is", "I'm looking for", "A shower thought I recently had", "My most irrational fear",
                "Together, we could", "I geek out on", "Green flags I look for", "The key to my heart is",
                "I'm weirdly attracted to", "My love language is", "Unusual skills", "I want someone who",
                "Let's debate this topic", "A life goal of mine", "Best travel story", "I'm convinced that",
                "We'll get along if", "Don't hate me if I", "This year, I really want to", "My greatest strength",
                "The one thing you should know about me is", "First round is on me if", "Try to guess this about me",
                "I bet you can't", "You should leave a comment if", "The hallmark of a good relationship is",
                "I recently discovered that", "My biggest date fail", "Never have I ever", "Give me travel tips for",
                "Change my mind about", "Do you agree or disagree that", "I'll fall for you if", "What I order for the table",
                "I'm the type of texter who", "The best way to ask me out is by", "My favorite line from a movie",
                "Biggest risk I've taken", "One thing I'll never do again", "I know the best spot in town for",
                "What if I told you that", "Something that's non-negotiable for me is", "A random fact I love is",
            ),
            chromeTexts = setOf(
                "Chat", "Profile", "Send", "Send a message", "Message", "Type a message", "Matches", "Likes", "Discover",
                "Standouts", "Unmatch", "Report", "Hide", "Remove", "Back", "More", "Close", "Done", "Cancel", "Edit", "Like",
                "Active now", "Active today", "Active recently", "New match", "Your turn", "Their turn", "Today", "Yesterday",
                "Sent", "Delivered", "Read", "Seen", "We met", "Share feedback", "Start the chat", "Voice note", "Video call",
            ),
        )

        fun forPackage(packageName: String): ScreenProfile? = if (packageName == ScreenReaderApps.HINGE) HINGE else null
    }
}

/** A visible text with where it sits. Holds text: never log it. */
class TextBlock(
    val text: String,
    val viewId: String?,
    val bounds: NodeBounds,
    val heading: Boolean,
    /** The node itself or one of its ancestors is clickable (a row, a button). */
    val inClickable: Boolean,
    /** Position in document order. */
    val order: Int,
) {
    override fun toString(): String = "TextBlock(${TextShape.of(text.length)}, id=$viewId)"
}

/** Two texts that are the only texts of their container, the upper one first. */
class TextPair(val upper: TextBlock, val lower: TextBlock, val containerClickable: Boolean) {
    override fun toString(): String = "TextPair($upper, $lower)"
}

/** Text of a snapshot in reading order. Images and input fields are skipped: their text is not profile text. */
object ScreenText {
    fun blocks(root: ScreenNode): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        collect(root, false, out)
        return out
    }

    private fun collect(n: ScreenNode, parentClickable: Boolean, out: MutableList<TextBlock>) {
        val clickable = parentClickable || n.clickable
        val text = n.text?.trim()
        if (!text.isNullOrEmpty() && !n.isImage && !n.editable) {
            out += TextBlock(text, n.viewId, n.bounds, n.heading, clickable, out.size)
        }
        n.children.forEach { collect(it, clickable, out) }
    }

    /** The lowest containers that hold exactly two texts, in document order. */
    fun pairs(root: ScreenNode): List<TextPair> {
        val all = blocks(root)
        val out = ArrayList<TextPair>()
        var cursor = 0

        // Returns how many text blocks the subtree holds, consuming them from `all` in order.
        fun visit(n: ScreenNode, parentClickable: Boolean): Int {
            val clickable = parentClickable || n.clickable
            val start = cursor
            val own = n.text?.trim()
            if (!own.isNullOrEmpty() && !n.isImage && !n.editable) cursor++
            val childCounts = n.children.map { visit(it, clickable) }
            val count = cursor - start
            if (count == 2 && childCounts.none { it == 2 }) {
                val a = all[start]
                val b = all[start + 1]
                val (upper, lower) = if (b.bounds.top < a.bounds.top) b to a else a to b
                out += TextPair(upper, lower, clickable)
            }
            return count
        }
        visit(root, false)
        return out.sortedBy { it.upper.order }
    }

    fun hasInput(root: ScreenNode): Boolean {
        var found = false
        root.walk { n, _ -> if (n.editable) found = true }
        return found
    }

    fun anyId(root: ScreenNode, fragments: Collection<String>): Boolean {
        if (fragments.isEmpty()) return false
        var found = false
        root.walk { n, _ -> if (ScreenProfile.idMatches(n.viewId, fragments)) found = true }
        return found
    }
}

/** The classifier's answer and why. */
data class ScreenClassification(val kind: ScreenKind, val source: EvidenceSource?)

/**
 * Decides what a snapshot shows. Order: view-id hints of the app's [ScreenProfile] first; then
 * structure: a known prompt question above an answer is a profile; a text input is a chat; three or
 * more tappable two-line rows are the match list; two or more plain heading/body blocks are a profile.
 */
object ScreenClassifier {
    const val MAX_QUESTION_CHARS = 100
    const val MIN_LIST_ROWS = 3
    const val MIN_STRUCTURAL_PAIRS = 2

    fun classify(snapshot: ScreenSnapshot, profile: ScreenProfile): ScreenClassification {
        val root = snapshot.root
        if (ScreenText.anyId(root, profile.chatScreenIds)) return ScreenClassification(ScreenKind.CHAT, EvidenceSource.ID_HINT)
        if (ScreenText.anyId(root, profile.profileScreenIds)) return ScreenClassification(ScreenKind.PROFILE, EvidenceSource.ID_HINT)
        if (ScreenText.anyId(root, profile.matchListIds)) return ScreenClassification(ScreenKind.MATCH_LIST, EvidenceSource.ID_HINT)

        val pairs = ScreenText.pairs(root)
        if (pairs.any { isPromptQuestion(it.upper, profile) && profile.isKnownPrompt(it.upper.text) }) {
            return ScreenClassification(ScreenKind.PROFILE, EvidenceSource.KNOWN_PROMPT)
        }
        if (ScreenText.hasInput(root)) return ScreenClassification(ScreenKind.CHAT, EvidenceSource.STRUCTURE)
        if (pairs.count { it.containerClickable } >= MIN_LIST_ROWS) return ScreenClassification(ScreenKind.MATCH_LIST, EvidenceSource.STRUCTURE)
        if (pairs.count { isStructuralPrompt(it, profile) } >= MIN_STRUCTURAL_PAIRS) {
            return ScreenClassification(ScreenKind.PROFILE, EvidenceSource.STRUCTURE)
        }
        return ScreenClassification(ScreenKind.OTHER, null)
    }

    fun isPromptQuestion(b: TextBlock, profile: ScreenProfile): Boolean =
        b.text.length <= MAX_QUESTION_CHARS && !profile.isChrome(b.text)

    /** A plain (not tappable) heading above a body, neither of them app chrome. */
    fun isStructuralPrompt(p: TextPair, profile: ScreenProfile): Boolean =
        !p.containerClickable && isPromptQuestion(p.upper, profile) && !profile.isChrome(p.lower.text) &&
            p.lower.text.length >= 2 && !p.upper.text.any { it.isDigit() }
}

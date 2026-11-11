package com.wingman.integrations.screenreader

/** Whose bubble a visible chat line is, judged by which edge it hugs. */
enum class ChatSide { THEIRS, MINE, UNKNOWN }

/** One visible chat line. Private: never log. */
class ChatLine(val side: ChatSide, val text: String, val order: Int) {
    override fun toString(): String = "ChatLine(side=$side, ${TextShape.of(text.length)})"
}

/**
 * What a chat screen showed at one moment: CONTEXT ONLY. It tells Wingman which match is open and
 * what is visible; it is not a message source. This type is never turned into a connector event, a
 * message, a draft request or anything sent to the backend; notifications stay the message source.
 */
class ChatContextSnapshot(val headerName: String?, val lines: List<ChatLine>, val capturedAtMillis: Long) {
    override fun toString(): String = "ChatContextSnapshot(header=${headerName != null}, lines=${lines.size})"
}

/** Reads the visible message list of a chat screen (sender side, text, order). */
object ChatScreenReader {
    private val NAME = Regex("^\\p{L}[\\p{L}\\p{M} .'’-]{0,39}$")
    private val TIME = Regex("^(\\d{1,2}:\\d{2}(\\s?[AaPp][Mm])?|\\d{1,2}/\\d{1,2}(/\\d{2,4})?|\\w{3,9} \\d{1,2}(, \\d{4})?)$")

    fun read(snapshot: ScreenSnapshot, profile: ScreenProfile): ChatContextSnapshot {
        val root = snapshot.root
        val blocks = ScreenText.blocks(root).filter { !profile.isChrome(it.text) }
        // The match's name: by view id, else the top-most name-like text.
        val header = blocks.firstOrNull { ScreenProfile.idMatches(it.viewId, profile.chatHeaderNameIds) }
            ?: blocks.filter { NAME.matches(it.text) }.minWithOrNull(compareBy<TextBlock> { it.bounds.top }.thenBy { it.order })
        val byId = blocks.filter { ScreenProfile.idMatches(it.viewId, profile.messageTextIds) }
        val bubbles = byId.ifEmpty {
            blocks.filter { it !== header && !TIME.matches(it.text) && (header == null || it.order > header.order) }
        }
        val lines = bubbles.mapIndexed { i, b -> ChatLine(side(b.bounds, root.bounds), b.text, i) }
        return ChatContextSnapshot(header?.text, lines, snapshot.capturedAtMillis)
    }

    fun side(bubble: NodeBounds, screen: NodeBounds): ChatSide {
        if (screen.width <= 0 || bubble.width <= 0) return ChatSide.UNKNOWN
        val leftGap = bubble.left - screen.left
        val rightGap = screen.right - bubble.right
        return when {
            leftGap < rightGap -> ChatSide.THEIRS
            rightGap < leftGap -> ChatSide.MINE
            else -> ChatSide.UNKNOWN
        }
    }
}

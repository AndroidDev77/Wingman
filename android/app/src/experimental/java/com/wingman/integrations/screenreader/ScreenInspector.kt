package com.wingman.integrations.screenreader

import com.wingman.integrations.notifications.NotificationStateStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Whether a screen exposes text to an accessibility reader at all. */
@Serializable
enum class ReadabilityVerdict(val label: String) {
    @SerialName("text_readable") TEXT_READABLE("text is readable"),

    @SerialName("no_readable_text") NO_READABLE_TEXT("no readable text (blocked or custom-drawn)"),
}

/** One node, redacted: structure and lengths only. */
@Serializable
data class InspectorNode(
    val depth: Int,
    @SerialName("class") val className: String?,
    @SerialName("view_id") val viewId: String?,
    /** `[left, top, right, bottom]` in pixels. */
    val bounds: List<Int>,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val heading: Boolean,
    val image: Boolean,
    /** `none` or `text(<n> chars)`: never the text. */
    val text: String,
    /** `none` or `desc(<n> chars)`: never the description. */
    val description: String,
    val children: Int,
)

/** A REDACTED snapshot of an allowlisted app's screen: safe to export and send. */
@Serializable
data class InspectorSnapshot(
    @SerialName("package") val packageName: String,
    @SerialName("captured_at_ms") val capturedAtMillis: Long,
    /** Wingman's own guess: `profile`, `chat`, `match_list` or `other`. */
    @SerialName("screen_guess") val screenGuess: String,
    val verdict: ReadabilityVerdict,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("text_node_count") val textNodeCount: Int,
    @SerialName("text_chars_total") val textCharsTotal: Int,
    @SerialName("nodes_with_view_id") val nodesWithViewId: Int,
    @SerialName("editable_nodes") val editableNodes: Int,
    val truncated: Boolean,
    val nodes: List<InspectorNode>,
) {
    /** One line for the in-app list. */
    fun summary(): String =
        "${ScreenReaderApps.label(packageName)} · $screenGuess · ${verdict.label} · $textNodeCount text nodes, $nodesWithViewId with ids"
}

/**
 * Builds [InspectorSnapshot]s. It reads [ScreenNode.textLength] and [ScreenNode.descriptionLength]
 * only, so it works on a snapshot taken WITHOUT text ([ReadMode.INSPECT]) and cannot leak text even
 * when the snapshot has it.
 */
object ScreenInspector {
    const val MAX_NODES = 600

    /** At least this many text-bearing, non-image nodes and this many characters to call it readable. */
    const val MIN_TEXT_NODES = 3
    const val MIN_TEXT_CHARS = 20

    fun redact(snapshot: ScreenSnapshot, screenGuess: ScreenKind = ScreenKind.OTHER): InspectorSnapshot {
        val nodes = ArrayList<InspectorNode>()
        var total = 0
        var textNodes = 0
        var textChars = 0
        var withId = 0
        var editable = 0
        snapshot.root.walk { n, depth ->
            total++
            if (n.viewId != null) withId++
            if (n.editable) editable++
            if (n.textLength > 0 && !n.isImage) {
                textNodes++
                textChars += n.textLength
            }
            if (nodes.size < MAX_NODES) {
                nodes += InspectorNode(
                    depth = depth,
                    className = n.className?.take(120),
                    viewId = n.viewId?.take(160),
                    bounds = listOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom),
                    clickable = n.clickable,
                    scrollable = n.scrollable,
                    editable = n.editable,
                    heading = n.heading,
                    image = n.isImage,
                    text = TextShape.of(n.textLength),
                    description = TextShape.ofDescription(n.descriptionLength),
                    children = n.children.size,
                )
            }
        }
        val verdict = if (textNodes >= MIN_TEXT_NODES && textChars >= MIN_TEXT_CHARS) {
            ReadabilityVerdict.TEXT_READABLE
        } else {
            ReadabilityVerdict.NO_READABLE_TEXT
        }
        return InspectorSnapshot(
            packageName = snapshot.packageName,
            capturedAtMillis = snapshot.capturedAtMillis,
            screenGuess = screenGuess.name.lowercase(),
            verdict = verdict,
            nodeCount = total,
            textNodeCount = textNodes,
            textCharsTotal = textChars,
            nodesWithViewId = withId,
            editableNodes = editable,
            truncated = total > nodes.size,
            nodes = nodes,
        )
    }
}

/** What the export file contains. */
@Serializable
data class InspectorExport(
    val format: String = "wingman.screen_inspector.v1",
    val note: String = "Redacted: view classes, view ids, bounds, flags and text LENGTHS only. No screen text.",
    @SerialName("exported_at_ms") val exportedAtMillis: Long,
    val snapshots: List<InspectorSnapshot>,
)

/**
 * Keeps the last [capacity] redacted snapshots in the state store. Consecutive snapshots with the
 * same structure (a redraw of the same screen) replace each other, so scrolling does not flood it.
 */
class InspectorLog(private val store: NotificationStateStore, private val capacity: Int = DEFAULT_CAPACITY) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(InspectorSnapshot.serializer())

    @Synchronized
    fun snapshots(): List<InspectorSnapshot> =
        store.get(KEY)?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()

    @Synchronized
    fun record(s: InspectorSnapshot) {
        val current = snapshots().toMutableList()
        val last = current.lastOrNull()
        if (last != null && last.packageName == s.packageName && fingerprint(last) == fingerprint(s)) {
            current[current.size - 1] = s
        } else {
            current += s
        }
        store.put(KEY, json.encodeToString(listSerializer, current.takeLast(capacity)))
    }

    @Synchronized
    fun clear() = store.remove(KEY)

    fun exportJson(nowMillis: Long): String =
        Json { prettyPrint = true; encodeDefaults = true }.encodeToString(InspectorExport.serializer(), InspectorExport(exportedAtMillis = nowMillis, snapshots = snapshots()))

    private fun fingerprint(s: InspectorSnapshot): String =
        s.screenGuess + "|" + s.nodes.joinToString(",") { "${it.depth}:${it.className}:${it.viewId}:${it.text != "none"}" }

    companion object {
        const val DEFAULT_CAPACITY = 20
        const val KEY = "sr:inspector:snapshots"
    }
}

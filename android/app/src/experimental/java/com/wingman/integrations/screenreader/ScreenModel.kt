package com.wingman.integrations.screenreader

import com.wingman.integrations.notifications.NotificationStateStore

/*
 * EXPERIMENTAL BUILD ONLY (D-032, `-Pwingman.experimental=true`): a READ-ONLY screen reader for the
 * dating apps already on the phone. This file and its siblings are pure Kotlin (no platform
 * imports), so the whole behaviour is JVM-tested with synthetic trees. Nothing here has ever seen a
 * real Hinge screen: every device behaviour is UNVERIFIED.
 *
 * Privacy rules that hold for every type in this package:
 *  - screen text is never logged ([ScreenReaderLog] takes counts and kinds only, and every type
 *    holding text has a redacted `toString`);
 *  - only TEXT is captured: nothing from images, and an image's description is never even copied
 *    into the model (only its length, for the redacted inspector);
 *  - nothing is inferred: a field exists only when the screen showed it as text.
 */

/** Screen rectangle in pixels. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2

    companion object {
        val EMPTY = NodeBounds(0, 0, 0, 0)
    }
}

/**
 * One node of a screen snapshot. [text] is the node's own visible text, or null when the node has
 * none OR when the snapshot was taken for the inspector only (then just [textLength] is kept).
 * There is deliberately no field for an image description's content: only [descriptionLength].
 */
data class ScreenNode(
    val className: String? = null,
    /** Full view-id resource name as the app exposes it, e.g. `co.example:id/title`. Not user data. */
    val viewId: String? = null,
    val text: String? = null,
    val textLength: Int = text?.length ?: 0,
    val descriptionLength: Int = 0,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val heading: Boolean = false,
    val bounds: NodeBounds = NodeBounds.EMPTY,
    val children: List<ScreenNode> = emptyList(),
) {
    /** Image-like widgets: their text (if any) and descriptions are never used. */
    val isImage: Boolean get() = className?.let { c -> IMAGE_CLASS_MARKERS.any { c.contains(it, ignoreCase = true) } } == true

    fun walk(depth: Int = 0, visit: (ScreenNode, Int) -> Unit) {
        visit(this, depth)
        children.forEach { it.walk(depth + 1, visit) }
    }

    fun count(): Int = 1 + children.sumOf { it.count() }

    override fun toString(): String = "ScreenNode(class=$className, id=$viewId, text=${TextShape.of(textLength)}, children=${children.size})"

    private companion object {
        val IMAGE_CLASS_MARKERS = listOf("ImageView", "ImageButton", "Image", "Photo", "Avatar", "VideoView", "SurfaceView", "TextureView")
    }
}

/** One screen of an allowlisted app at one moment. */
data class ScreenSnapshot(
    val packageName: String,
    val capturedAtMillis: Long,
    val root: ScreenNode,
) {
    override fun toString(): String = "ScreenSnapshot(pkg=$packageName, nodes=${root.count()})"
}

/** The coarse, content-free description of a text: its length only. */
object TextShape {
    fun of(length: Int): String = if (length <= 0) "none" else "text($length chars)"
    fun ofDescription(length: Int): String = if (length <= 0) "none" else "desc($length chars)"
}

/**
 * The package allowlist. Events from any other package are dropped before any text is read: the
 * service configuration lists exactly [ALL], and [ScreenReaderGate] checks again in code.
 */
object ScreenReaderApps {
    const val HINGE = "co.hinge.app"
    const val TINDER = "com.tinder"
    const val BUMBLE = "com.bumble.app"

    /** Profile capture exists for these only. */
    val CAPTURE: Set<String> = setOf(HINGE)

    /** The redacted inspector may also look at these (no text is ever kept for them). */
    val INSPECTOR: Set<String> = setOf(HINGE, TINDER, BUMBLE)

    val ALL: Set<String> = CAPTURE + INSPECTOR

    fun platformOf(packageName: String): String? = when (packageName) {
        HINGE -> "hinge"
        TINDER -> "tinder"
        BUMBLE -> "bumble"
        else -> null
    }

    fun label(packageName: String): String = when (packageName) {
        HINGE -> "Hinge"
        TINDER -> "Tinder"
        BUMBLE -> "Bumble"
        else -> packageName
    }
}

/**
 * The user's choices. Everything is OFF by default; capture also needs the explainer to have been
 * accepted. Stored in the screen reader's own app-private state file.
 */
class ScreenReaderSettings(private val store: NotificationStateStore) {
    var explainerAccepted: Boolean
        get() = store.get(KEY_EXPLAINER) == EXPLAINER_VERSION
        set(value) = if (value) store.put(KEY_EXPLAINER, EXPLAINER_VERSION) else store.remove(KEY_EXPLAINER)

    fun captureEnabled(packageName: String): Boolean =
        explainerAccepted && packageName in ScreenReaderApps.CAPTURE && store.get(KEY_CAPTURE + packageName) == "on"

    /** Refused (returns false) for a package without capture support or before the explainer. */
    fun setCaptureEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!enabled) {
            store.remove(KEY_CAPTURE + packageName)
            return true
        }
        if (packageName !in ScreenReaderApps.CAPTURE || !explainerAccepted) return false
        store.put(KEY_CAPTURE + packageName, "on")
        return true
    }

    fun inspectorEnabled(packageName: String): Boolean =
        packageName in ScreenReaderApps.INSPECTOR && store.get(KEY_INSPECT + packageName) == "on"

    fun setInspectorEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!enabled) {
            store.remove(KEY_INSPECT + packageName)
            return true
        }
        if (packageName !in ScreenReaderApps.INSPECTOR) return false
        store.put(KEY_INSPECT + packageName, "on")
        return true
    }

    /** True when anything at all is switched on. */
    fun anythingEnabled(): Boolean = ScreenReaderApps.ALL.any { captureEnabled(it) || inspectorEnabled(it) }

    companion object {
        const val EXPLAINER_VERSION = "d032-v1"
        const val KEY_EXPLAINER = "sr:settings:explainer"
        const val KEY_CAPTURE = "sr:settings:capture:"
        const val KEY_INSPECT = "sr:settings:inspect:"
    }
}

/** What may be done with one package's screen right now. */
enum class ReadMode(val inspect: Boolean, val capture: Boolean) {
    /** Not allowlisted, or switched off: the event is dropped and nothing is read. */
    DROP(false, false),

    /** Structure and text LENGTHS only; no text is copied. */
    INSPECT(true, false),
    CAPTURE(false, true),
    INSPECT_AND_CAPTURE(true, true),
    ;

    val reads: Boolean get() = inspect || capture
}

/** The in-code allowlist and toggle check. The service asks it BEFORE touching a window. */
class ScreenReaderGate(private val settings: ScreenReaderSettings) {
    fun mode(packageName: String?): ReadMode {
        if (packageName == null || packageName !in ScreenReaderApps.ALL) return ReadMode.DROP
        val inspect = settings.inspectorEnabled(packageName)
        val capture = settings.captureEnabled(packageName)
        return when {
            inspect && capture -> ReadMode.INSPECT_AND_CAPTURE
            capture -> ReadMode.CAPTURE
            inspect -> ReadMode.INSPECT
            else -> ReadMode.DROP
        }
    }
}

/** Redacted log sink: event names with counts and kinds only. Never pass screen text. */
fun interface ScreenReaderLog {
    fun log(event: String, fields: Map<String, String>)

    companion object {
        val NONE = ScreenReaderLog { _, _ -> }
    }
}

/** Plain-language text shown before anything can be switched on. */
object ScreenReaderExplainer {
    const val TITLE = "Let Wingman read a match's profile from your screen (experimental)"

    val BODY: List<String> = listOf(
        "This uses an Android accessibility service. Android will warn you that such a service can see what is on your screen. That warning is accurate, so here is exactly what Wingman does with it.",
        "Wingman only looks at the apps you switch on below. Today that is Hinge. Every other app is ignored: Wingman is not told about their screens at all.",
        "When you open a match's profile, Wingman copies the TEXT it shows: the name, the prompts and their answers, and basics that appear as text (such as job or hometown). It also reads the open chat to know which match you are looking at.",
        "Wingman never reads photos or photo descriptions, and never guesses anything that is not written on the screen.",
        "Wingman only reads. It never taps, types, scrolls, swipes, likes or sends anything in another app.",
        "The text is saved on this phone and uploaded to your Wingman account so reply drafts can refer to it. It is never written to logs. You can delete any captured profile; that deletes it from your account too.",
        "Chat messages seen on screen are used only to tell matches apart. They are not stored as messages and never trigger a draft.",
        "To stop: switch the app off here. Reading stops immediately. You can also turn the service off in Android's Accessibility settings.",
        "This is an experimental build. Nothing here has been tested against the real app yet, and reading another app's screen may be against that app's terms.",
    )

    const val ACCEPT_LABEL = "I understand what is read"

    val INSPECTOR_NOTE: String =
        "The inspector records the layout of the screen only: view classes, view ids, positions and the LENGTH of each text " +
            "(for example \"text(42 chars)\"). It never records the text itself. Use it to check whether an app's screen can be read at all."
}

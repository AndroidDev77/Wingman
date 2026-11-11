package com.wingman.integrations.screenreader

import com.wingman.integrations.notifications.InMemoryNotificationStateStore
import com.wingman.integrations.notifications.NotificationClock
import com.wingman.integrations.notifications.NotificationStateStore

/*
 * SYNTHETIC trees only. No real person, profile or message appears here, and nothing in this file
 * was taken from a real app screen: the layouts are guesses of what a dating app could expose.
 */

const val SCREEN_W = 1080
const val SCREEN_H = 2400

/** Every synthetic text a test puts on a "screen". Privacy tests scan logs and exports for them. */
object Sentinels {
    const val NAME = "Synthetica Riverstone"
    const val OTHER_NAME = "Fabricio Quillfeather"
    const val Q_SUNDAY = "Typical Sunday"
    const val A_SUNDAY = "Zebra-striped farmers market, then a long trail run."
    const val Q_SHOWER = "A shower thought I recently had"
    const val A_SHOWER_PART = "Do purple fish get thir"
    const val A_SHOWER = "Do purple fish get thirsty underwater?"
    const val Q_SIMPLE = "My simple pleasures"
    const val A_SIMPLE = "Lukewarm oolong and a dog-eared paperback."
    const val Q_CUSTOM = "Fictional custom heading"
    const val A_CUSTOM = "Plausible body text for a made-up card."
    const val HEIGHT = "5' 7\""
    const val JOB = "Kazoo tuner"
    const val TOWN = "Example Falls"
    const val PHOTO_TEXT = "Caption painted on a photo"
    const val THEIRS_1 = "Hello from the synthetic match"
    const val MINE_1 = "A reply typed by the synthetic user"
    const val THEIRS_2 = "Second line from the synthetic match"
    const val TYPING = "Half-typed text in the input box"
    const val PREVIEW = "Preview line of a synthetic chat"
    const val BANK = "Balance 12,345.67 at Example Bank"

    val ALL = listOf(
        NAME, OTHER_NAME, A_SUNDAY, A_SHOWER_PART, A_SHOWER, A_SIMPLE, Q_CUSTOM, A_CUSTOM, HEIGHT, JOB, TOWN, PHOTO_TEXT,
        THEIRS_1, MINE_1, THEIRS_2, TYPING, PREVIEW, BANK,
    )
}

fun box(top: Int, bottom: Int, left: Int = 0, right: Int = SCREEN_W) = NodeBounds(left, top, right, bottom)

fun group(
    vararg children: ScreenNode,
    id: String? = null,
    clickable: Boolean = false,
    scrollable: Boolean = false,
    bounds: NodeBounds = NodeBounds.EMPTY,
    cls: String = "android.view.ViewGroup",
) = ScreenNode(className = cls, viewId = id, clickable = clickable, scrollable = scrollable, bounds = bounds, children = children.toList())

fun label(text: String, top: Int, id: String? = null, heading: Boolean = false, left: Int = 40, right: Int = SCREEN_W - 40, height: Int = 60) =
    ScreenNode(className = "android.widget.TextView", viewId = id, text = text, heading = heading, bounds = NodeBounds(left, top, right, top + height))

fun photo(top: Int, descriptionLength: Int = 44, text: String? = null) =
    ScreenNode(className = "android.widget.ImageView", text = text, descriptionLength = descriptionLength, bounds = box(top, top + 900))

fun promptCard(question: String, answer: String, top: Int, qId: String? = null, aId: String? = null) = group(
    label(question, top + 20, id = qId, height = 50),
    label(answer, top + 90, id = aId, height = 160),
    bounds = box(top, top + 280),
)

fun screen(pkg: String, at: Long, vararg children: ScreenNode) =
    ScreenSnapshot(pkg, at, group(*children, bounds = box(0, SCREEN_H), cls = "android.widget.FrameLayout"))

/** Top of a profile: name, a photo, a basics row, the first prompt and a second one cut off by the screen edge. */
fun profileTop(at: Long = 1_000, name: String = Sentinels.NAME, pkg: String = ScreenReaderApps.HINGE) = screen(
    pkg, at,
    label(name, 150, heading = true, height = 80),
    photo(260, text = Sentinels.PHOTO_TEXT),
    group(
        label(Sentinels.HEIGHT, 1180, left = 40, right = 240),
        label(Sentinels.JOB, 1180, left = 280, right = 560),
        label(Sentinels.TOWN, 1180, left = 600, right = 900),
        bounds = box(1170, 1250),
    ),
    promptCard(Sentinels.Q_SUNDAY, Sentinels.A_SUNDAY, 1300),
    promptCard(Sentinels.Q_SHOWER, Sentinels.A_SHOWER_PART, 1620),
)

/** The same profile scrolled down: the name is gone, the cut-off answer is complete, new cards appear. */
fun profileScrolled(at: Long = 2_000, pkg: String = ScreenReaderApps.HINGE) = screen(
    pkg, at,
    promptCard(Sentinels.Q_SHOWER, Sentinels.A_SHOWER, 100),
    photo(420),
    promptCard(Sentinels.Q_SIMPLE, Sentinels.A_SIMPLE, 1360),
    promptCard(Sentinels.Q_CUSTOM, Sentinels.A_CUSTOM, 1680),
)

fun chatScreen(at: Long = 3_000, name: String = Sentinels.NAME, pkg: String = ScreenReaderApps.HINGE) = screen(
    pkg, at,
    label(name, 120, height = 80),
    label("Chat", 220, left = 40, right = 500),
    label("Profile", 220, left = 580, right = 1040),
    label(Sentinels.THEIRS_1, 600, left = 40, right = 700),
    label("10:42", 680, left = 40, right = 200, height = 40),
    label(Sentinels.MINE_1, 760, left = 380, right = SCREEN_W - 40),
    label(Sentinels.THEIRS_2, 920, left = 40, right = 760),
    ScreenNode(className = "android.widget.EditText", text = Sentinels.TYPING, editable = true, bounds = box(2200, 2320, 40, 860)),
    group(label("Send", 2220, left = 900, right = 1040), clickable = true, bounds = box(2200, 2320, 880, SCREEN_W)),
)

fun matchList(at: Long = 4_000, vararg names: String = arrayOf(Sentinels.NAME, Sentinels.OTHER_NAME, "Third Fictional"), pkg: String = ScreenReaderApps.HINGE) = screen(
    pkg, at,
    group(
        *names.mapIndexed { i, n ->
            group(
                photo(300 + i * 200, descriptionLength = 20),
                label(n, 300 + i * 200, left = 240),
                label(Sentinels.PREVIEW, 370 + i * 200, left = 240),
                clickable = true, bounds = box(290 + i * 200, 480 + i * 200),
            )
        }.toTypedArray(),
        scrollable = true, bounds = box(200, SCREEN_H),
    ),
)

/** A screen with nothing a reader can use: only image-like and empty nodes. */
fun opaqueScreen(at: Long = 5_000, pkg: String = ScreenReaderApps.HINGE) = screen(
    pkg, at,
    ScreenNode(className = "android.view.SurfaceView", bounds = box(0, SCREEN_H)),
    photo(100, descriptionLength = 0),
    group(bounds = box(1000, 1200)),
)

fun bankScreen(at: Long = 6_000) = screen("com.example.bank", at, label(Sentinels.BANK, 300), label("Transfer", 500))

class FakeClock(var now: Long = 10_000) : NotificationClock {
    override fun nowMillis(): Long = now
}

class RecordingScreenLog : ScreenReaderLog {
    val lines = ArrayList<String>()
    override fun log(event: String, fields: Map<String, String>) {
        lines += event + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}

/** A core over an in-memory store, with Hinge capture and the explainer switched on unless told otherwise. */
class ReaderWorld(
    val store: NotificationStateStore = InMemoryNotificationStateStore(),
    capture: Boolean = true,
    inspect: Boolean = false,
    conversationNames: () -> Collection<String> = { emptyList() },
    screenProfiles: (String) -> ScreenProfile? = ScreenProfile.Companion::forPackage,
) {
    val settings = ScreenReaderSettings(store)
    val clock = FakeClock()
    val log = RecordingScreenLog()
    val captured = ArrayList<CapturedProfile>()
    private var ids = 0
    val profiles = CapturedProfileStore(store)
    val inspector = InspectorLog(store)
    val core = ScreenReaderCore(
        settings, inspector, profiles, clock, log, screenProfiles,
        conversationNames = { conversationNames() },
        newId = { "local-%04d".format(++ids) },
        onCaptured = { captured += it },
    )

    init {
        if (capture) {
            settings.explainerAccepted = true
            check(settings.setCaptureEnabled(ScreenReaderApps.HINGE, true))
        }
        if (inspect) check(settings.setInspectorEnabled(ScreenReaderApps.HINGE, true))
    }

    /** Everything the store holds, as one string (to look for text that must not be there). */
    fun storedText(): String = store.keys("").joinToString("\n") { it + "=" + store.get(it) }
}

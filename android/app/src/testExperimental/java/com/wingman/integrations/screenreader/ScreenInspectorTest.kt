package com.wingman.integrations.screenreader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inspector output is structure and lengths only, with a verdict per screen. */
class ScreenInspectorTest {
    @Test fun `a redacted snapshot holds classes ids bounds flags and text lengths - never text`() {
        val s = ScreenInspector.redact(profileTop(), ScreenKind.PROFILE)
        assertEquals("co.hinge.app", s.packageName)
        assertEquals("profile", s.screenGuess)
        assertEquals(ReadabilityVerdict.TEXT_READABLE, s.verdict)
        assertEquals("text is readable", s.verdict.label)
        val name = s.nodes.first { it.heading }
        assertEquals("text(${Sentinels.NAME.length} chars)", name.text)
        assertEquals("android.widget.TextView", name.className)
        assertEquals(listOf(40, 150, 1040, 230), name.bounds)
        assertEquals(1, name.depth)
        val image = s.nodes.first { it.image }
        assertEquals("desc(44 chars)", image.description)
        // The photo's text is counted as a length only and does not make the screen "readable".
        assertEquals("text(${Sentinels.PHOTO_TEXT.length} chars)", image.text)
        assertEquals(8, s.textNodeCount)
        assertEquals(s.nodeCount, s.nodes.size)
        assertFalse(s.truncated)
        assertTrue(s.summary().startsWith("Hinge · profile · text is readable"))
    }

    @Test fun `a screen with nothing readable gets the blocked-or-custom-drawn verdict`() {
        val s = ScreenInspector.redact(opaqueScreen())
        assertEquals(ReadabilityVerdict.NO_READABLE_TEXT, s.verdict)
        assertEquals("no readable text (blocked or custom-drawn)", s.verdict.label)
        assertEquals(0, s.textNodeCount)
        // A couple of short labels are not enough either.
        val sparse = ScreenInspector.redact(screen(ScreenReaderApps.HINGE, 1, label("OK", 10), label("Hi", 20)))
        assertEquals(ReadabilityVerdict.NO_READABLE_TEXT, sparse.verdict)
    }

    @Test fun `the export contains none of the screen's text`() {
        val w = ReaderWorld(inspect = true)
        w.core.onSnapshot(profileTop())
        w.core.onSnapshot(profileScrolled())
        w.core.onSnapshot(chatScreen())
        w.core.onSnapshot(matchList())
        w.core.onSnapshot(opaqueScreen())
        val export = w.inspector.exportJson(nowMillis = 99)
        for (s in Sentinels.ALL + listOf(Sentinels.Q_SUNDAY, Sentinels.Q_SHOWER, Sentinels.Q_SIMPLE, "Send", "10:42")) {
            assertFalse("export contains \"$s\"", export.contains(s))
        }
        val json = Json.parseToJsonElement(export).jsonObject
        assertEquals("wingman.screen_inspector.v1", json["format"]!!.jsonPrimitive.content)
        val snapshots = json["snapshots"]!!.jsonArray
        assertEquals(listOf("profile", "profile", "chat", "match_list", "other"), snapshots.map { it.jsonObject["screen_guess"]!!.jsonPrimitive.content })
        assertEquals(
            listOf("text_readable", "text_readable", "text_readable", "text_readable", "no_readable_text"),
            snapshots.map { it.jsonObject["verdict"]!!.jsonPrimitive.content },
        )
        // Every text value in the export is `none` or a length.
        val shape = Regex("^(none|text\\(\\d+ chars\\))$")
        for (snap in snapshots) for (n in snap.jsonObject["nodes"]!!.jsonArray) {
            assertTrue(shape.matches(n.jsonObject["text"]!!.jsonPrimitive.content))
            assertTrue(Regex("^(none|desc\\(\\d+ chars\\))$").matches(n.jsonObject["description"]!!.jsonPrimitive.content))
        }
        // The chat screen shows its input box as an editable node with a length.
        assertEquals(1, snapshots[2].jsonObject["editable_nodes"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `it works on a snapshot that was taken without any text`() {
        // What the service builds in inspector-only mode: lengths, no strings.
        fun strip(n: ScreenNode): ScreenNode = n.copy(text = null, textLength = n.textLength, children = n.children.map(::strip))
        val full = profileTop()
        val bare = full.copy(root = strip(full.root))
        var texts = 0
        bare.root.walk { n, _ -> if (n.text != null) texts++ }
        assertEquals(0, texts)
        assertEquals(ScreenInspector.redact(full), ScreenInspector.redact(bare))
        assertEquals(ReadabilityVerdict.TEXT_READABLE, ScreenInspector.redact(bare).verdict)
    }

    @Test fun `tinder and bumble can be inspected - with no screen definition and no capture`() {
        val w = ReaderWorld(capture = false)
        assertTrue(w.settings.setInspectorEnabled(ScreenReaderApps.TINDER, true))
        assertEquals(SnapshotOutcome.INSPECTED, w.core.onSnapshot(profileTop(pkg = ScreenReaderApps.TINDER)))
        assertEquals(SnapshotOutcome.DROPPED, w.core.onSnapshot(profileTop(pkg = ScreenReaderApps.BUMBLE)))
        val only = w.inspector.snapshots().single()
        assertEquals("com.tinder", only.packageName)
        assertEquals("other", only.screenGuess)
        w.core.flush()
        assertTrue(w.profiles.all().isEmpty())
    }

    @Test fun `the log keeps the last N snapshots and folds redraws of the same screen`() {
        val w = ReaderWorld(inspect = true)
        repeat(5) { w.core.onSnapshot(profileTop(at = 1_000L + it)) }
        assertEquals("redraws replace each other", 1, w.inspector.snapshots().size)
        assertEquals(1_004L, w.inspector.snapshots().single().capturedAtMillis)
        val log = InspectorLog(w.store, capacity = 3)
        for (i in 0 until 7) log.record(ScreenInspector.redact(screen(ScreenReaderApps.HINGE, 2_000L + i, *Array(i + 1) { label("x".repeat(9), it * 10) })))
        assertEquals(listOf(2_004L, 2_005L, 2_006L), log.snapshots().map { it.capturedAtMillis })
        log.clear()
        assertTrue(log.snapshots().isEmpty())
    }

    @Test fun `a huge tree is truncated and says so`() {
        val big = screen(ScreenReaderApps.HINGE, 1, *Array(ScreenInspector.MAX_NODES + 50) { label("node text $it", it) })
        val s = ScreenInspector.redact(big)
        assertTrue(s.truncated)
        assertEquals(ScreenInspector.MAX_NODES, s.nodes.size)
        assertEquals(ScreenInspector.MAX_NODES + 51, s.nodeCount)
    }
}

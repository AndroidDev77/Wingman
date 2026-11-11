package com.wingman.integrations.screenreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Sentinel tests: no captured text in logs or in any `toString`, and nothing but text is captured. */
class ScreenReaderPrivacyTest {
    private val secrets = Sentinels.ALL + listOf(Sentinels.Q_SUNDAY, Sentinels.Q_SHOWER, Sentinels.Q_SIMPLE)

    private fun fullRun(): ReaderWorld {
        val w = ReaderWorld(inspect = true)
        w.core.onSnapshot(bankScreen())
        w.core.onSnapshot(matchList(at = 100))
        w.core.onSnapshot(chatScreen(at = 500))
        w.core.onSnapshot(profileTop(at = 1_000))
        w.core.onSnapshot(profileScrolled(at = 2_000))
        w.core.onSnapshot(chatScreen(at = 2_500))
        w.core.onSnapshot(opaqueScreen(at = 3_000))
        w.core.onSnapshot(profileTop(at = 4_000, name = Sentinels.OTHER_NAME))
        w.core.onIdle(4_000 + ScreenReaderCore.IDLE_FINISH_MS)
        w.settings.setCaptureEnabled(ScreenReaderApps.HINGE, false)
        w.core.stop(ScreenReaderApps.HINGE)
        return w
    }

    @Test fun `no captured text is ever logged`() {
        val w = fullRun()
        assertEquals(2, w.profiles.all().size)
        assertTrue("the run did log something", w.log.lines.size >= 5)
        for (line in w.log.lines) for (s in secrets) assertFalse("log line \"$line\" contains \"$s\"", line.contains(s))
        // Log lines are an event name plus key=value pairs of counts, kinds and booleans.
        val shape = Regex("""^sr\.[a-z_]+( [a-z_]+=[A-Za-z0-9_]+)*$""")
        for (line in w.log.lines) assertTrue(line, shape.matches(line))
    }

    @Test fun `no type prints captured text`() {
        val w = fullRun()
        val top = profileTop()
        val fragment = ProfileExtractor.extract(top, ScreenProfile.HINGE)
        val chat = ChatScreenReader.read(chatScreen(), ScreenProfile.HINGE)
        val session = ProfileCaptureSession(1).also { it.merge(fragment, 2) }
        val printed = listOf(
            top.toString(), top.root.toString(), top.root.children.joinToString { it.toString() },
            ScreenText.blocks(top.root).joinToString { it.toString() }, ScreenText.pairs(top.root).joinToString { it.toString() },
            fragment.toString(), fragment.prompts.joinToString { it.toString() }, fragment.basics.values.joinToString { it.toString() },
            session.result().toString(), chat.toString(), chat.lines.joinToString { it.toString() },
            w.profiles.all().joinToString { it.toString() }, w.profiles.all().joinToString { it.matchKey.toString() },
            w.captured.joinToString { it.toString() }, ScreenInspector.redact(top).toString(),
        )
        for (text in printed) for (s in secrets) assertFalse("\"$text\" contains \"$s\"", text.contains(s))
    }

    @Test fun `only the captured profile itself holds text on the device - and only its text`() {
        val w = fullRun()
        val stored = w.storedText()
        // Chat lines, input text, list previews, photo text and other apps never reach the state file.
        for (s in listOf(Sentinels.THEIRS_1, Sentinels.MINE_1, Sentinels.THEIRS_2, Sentinels.TYPING, Sentinels.PREVIEW, Sentinels.PHOTO_TEXT, Sentinels.BANK)) {
            assertFalse(s, stored.contains(s))
        }
        // The inspector entries in the same store are redacted.
        val inspector = w.store.get(InspectorLog.KEY)!!
        for (s in secrets) assertFalse(s, inspector.contains(s))
        // Profile text lives under the profile keys only.
        for (key in w.store.keys("")) {
            if (key.startsWith(CapturedProfileStore.PREFIX)) continue
            for (s in secrets) assertFalse("$key holds \"$s\"", w.store.get(key)!!.contains(s))
        }
    }

    @Test fun `static - the android adapters log nothing they read`() {
        val appDir = File("src").let { if (it.isDirectory) File(".") else File("android/app") }
        val dir = File(appDir, "src/experimental/java/com/wingman/integrations/screenreader")
        val logLines = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { f -> f.readLines().filter { Regex("""\bLog\.[a-z]+\(""").containsMatchIn(it) || it.contains("println(") || it.contains("printStackTrace") }.map { f.name to it.trim() } }.toList()
        // One log statement in the whole package: the redacted sink of the runtime.
        assertEquals(listOf("ScreenReaderRuntime.kt"), logLines.map { it.first })
        assertTrue(logLines.single().second.startsWith("Log.i(LOG_TAG, event + "))
        // The pure core logs through ScreenReaderLog only, with string-built counts.
        val core = File(dir, "ScreenReaderCore.kt").readText() + File(dir, "ProfileSync.kt").readText()
        val logged = Regex("""log\.log\(\s*"([a-z_.]+)",\s*mapOf\(([\s\S]*?)\)\s*,?\s*\)""").findAll(core).toList()
        assertTrue(logged.size >= 5)
        for (call in logged) {
            val args = call.groupValues[2]
            for (word in listOf(".text", "displayName", ".answer", ".question", ".value", "headerName", "contactName", "unlabeled")) {
                assertFalse("${call.groupValues[1]} logs $word", args.contains(word))
            }
        }
    }
}

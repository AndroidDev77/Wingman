package com.wingman.integrations.screenreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D-032 static checks: the screen reader is READ-ONLY. These scan the package's sources (comments
 * included, so the words cannot hide anywhere), the service configuration and the manifest entries.
 */
class ScreenReaderReadOnlyTest {
    private val appDir = File("src").let { if (it.isDirectory) File(".") else File("android/app") }
    private val packageDir = File(appDir, "src/experimental/java/com/wingman/integrations/screenreader")
    private val sources: List<File> get() = packageDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    private val config = File(appDir, "src/experimental/res/xml/wingman_screen_reader.xml")
    private val entries = File(appDir, "src/experimental/AndroidManifest-entries.xml")

    /** Every way an accessibility service can act on another app, change its text or capture pixels. */
    private val forbidden = listOf(
        "performAction", "dispatchGesture", "performGlobalAction", "GestureDescription", "setText", "ACTION_SET_TEXT", "ACTION_CLICK",
        "ACTION_LONG_CLICK", "ACTION_SCROLL", "ACTION_PASTE", "ACTION_FOCUS", "ACTION_SELECT", "ACTION_IME_ENTER", "ACTION_DISMISS",
        "ACTION_ARGUMENT", "AccessibilityAction", "GLOBAL_ACTION", "takeScreenshot", "SoftKeyboardController", "InputMethod",
        "MagnificationController", "FingerprintGestureController", "setServiceInfo", "ClipboardManager", "Instrumentation", "UiAutomation",
    )

    @Test fun `the package exists with its service and reader`() {
        assertTrue(packageDir.isDirectory)
        val names = sources.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("WingmanScreenReaderService.kt", "AccessibilityNodeReader.kt", "ScreenReaderCore.kt", "ProfileExtractor.kt")))
        assertTrue(sources.size >= 12)
    }

    @Test fun `no source in the package can click type gesture or press a system button`() {
        val offenders = sources.flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `the check itself catches each forbidden call`() {
        for (planted in listOf("node.performAction(16)", "dispatchGesture(g, null, null)", "performGlobalAction(1)", "input.setText(\"x\")", "ACTION_SET_TEXT")) {
            assertTrue(planted, forbidden.any { planted.contains(it) })
        }
    }

    @Test fun `only the service and the node reader touch the accessibility API - and only to read`() {
        val users = sources.filter { it.readText().contains("android.accessibilityservice") || it.readText().contains("android.view.accessibility") }.map { it.name }.toSet()
        assertEquals(setOf("WingmanScreenReaderService.kt", "AccessibilityNodeReader.kt"), users)
        // Everything the node reader asks a node for.
        val reader = File(packageDir, "android/AccessibilityNodeReader.kt").readText()
        val asked = Regex("""\bnode\.(\w+)""").findAll(reader).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("className", "isPassword", "text", "getBoundsInScreen", "childCount", "getChild", "viewIdResourceName", "contentDescription", "isClickable", "isScrollable", "isEditable", "isHeading"),
            asked,
        )
        // A content description is only ever measured, never copied.
        assertTrue(reader.contains("node.contentDescription?.length"))
        assertEquals(1, Regex("contentDescription").findAll(reader).count())
        // The pure core has no platform import at all.
        val pure = sources.filter { it.parentFile.name != "android" }
        assertTrue(pure.none { f -> f.readLines().any { it.startsWith("import android.") || it.startsWith("import androidx.") } })
    }

    @Test fun `the service configuration asks only for what reading needs`() {
        val xml = config.readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
        fun attr(name: String) = Regex("""android:$name="([^"]*)"""").find(xml)?.groupValues?.get(1)
        assertEquals("typeWindowStateChanged|typeWindowContentChanged", attr("accessibilityEventTypes"))
        assertEquals("true", attr("canRetrieveWindowContent"))
        assertEquals(ScreenReaderApps.ALL, attr("packageNames")!!.split(",").toSet())
        assertEquals(setOf("flagReportViewIds", "flagIncludeNotImportantViews"), attr("accessibilityFlags")!!.split("|").toSet())
        val attributes = Regex("""android:(\w+)=""").findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("accessibilityEventTypes", "accessibilityFeedbackType", "accessibilityFlags", "canRetrieveWindowContent", "description", "notificationTimeout", "packageNames", "summary"),
            attributes,
        )
        for (word in listOf("canPerformGestures", "canRequestTouchExplorationMode", "canRequestFilterKeyEvents", "canTakeScreenshot", "canControlMagnification", "canRequestFingerprintGestures", "flagRequestFilterKeyEvents", "flagRequestTouchExplorationMode", "typeAllMask")) {
            assertFalse(word, config.readText().contains(word))
        }
    }

    @Test fun `the manifest entry is the experimental one and is bound by the system only`() {
        val xml = entries.readText()
        val service = Regex("""<service[\s\S]*?</service>""").findAll(xml).single { it.value.contains("screenreader") }.value
        assertTrue(service.contains("""android:name=".integrations.screenreader.android.WingmanScreenReaderService""""))
        assertTrue(service.contains("""android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE""""))
        assertTrue(service.contains("""android:resource="@xml/wingman_screen_reader""""))
        assertTrue(xml.contains("""android:name=".integrations.screenreader.android.ScreenReaderActivity""""))
        val activity = Regex("""<activity[\s\S]*?/>""").findAll(xml).single { it.value.contains("ScreenReaderActivity") }.value
        assertTrue(activity.contains("""android:exported="false""""))
        // No permission is requested for it: reading the screen is granted by the user in Android's settings.
        assertFalse(xml.contains("<uses-permission"))
    }

    @Test fun `it is wired without any hinge client identity`() {
        val modules = File(appDir, "src/experimental/java/com/wingman/experimental/ExperimentalModules.kt").readText()
        assertTrue(modules.contains("ScreenReaderModule.init(context, container)"))
        // The settings row is added before, and independently of, the connector check.
        assertTrue(modules.indexOf("entries += ScreenReaderModule.settingsEntry()") in 0 until modules.indexOf("HingeModule.connector() != null"))
        for (f in sources) {
            val code = f.readText()
            assertFalse("${f.name} depends on the Hinge connector", code.contains("HingeBuildFlags") || code.contains("BuildConfig.HINGE") || code.contains("integrations.hinge."))
        }
    }
}

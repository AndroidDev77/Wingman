package com.wingman.e2e

import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/*
 * WING-025: UiAutomator helpers that drive the REAL app exactly as a user does, through the
 * accessibility tree. Unlike the Compose test rule, UiAutomator does not replace the app's
 * Recomposer or effect dispatcher, so LaunchedEffects run on the app's own main dispatcher as in
 * production. (Under the Compose rule, the first-launch onboarding LaunchedEffect resumed on
 * OkHttp's thread and NavController.navigate threw "must be called on the main thread": a
 * test-environment artifact, not an app bug.) Waits are condition-based (Until.hasObject).
 *
 * Compose merges a clickable/selectable row's texts into one node joined with ", ", so rows are
 * matched with textStartsWith/textContains; buttons and plain Text nodes are matched exactly.
 */
const val UI_TIMEOUT_MS = 30_000L

/** Swipe speed for [E2eDevice.scrollTo], in px/s: slow enough that Compose barely flings. */
const val SCROLL_SPEED = 1_500

class E2eDevice(private val timeoutMs: Long = UI_TIMEOUT_MS) {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun has(sel: BySelector): Boolean = device.hasObject(sel)

    fun waitFor(sel: BySelector, what: String, timeoutMs: Long = this.timeoutMs): UiObject2 {
        dismissSystemAnrDialog()
        if (!device.wait(Until.hasObject(sel), timeoutMs / 2)) {
            hideKeyboard() // the soft keyboard can cover bottom bars and buttons
            dismissSystemAnrDialog()
            device.wait(Until.hasObject(sel), timeoutMs / 2)
        }
        return device.findObject(sel) ?: run {
            dumpHierarchy("missing $what")
            throw AssertionError("UI never showed $what within ${timeoutMs}ms")
        }
    }

    /** Prints the current window hierarchy (texts and bounds) to logcat, for a failed wait. */
    fun dumpHierarchy(reason: String) {
        val out = java.io.ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        println("WING025-UI hierarchy ($reason):")
        out.toString().lineSequence()
            .filter { it.contains("text=\"") && !it.contains("text=\"\"") || it.contains("content-desc=\"") && !it.contains("content-desc=\"\"") }
            .forEach { line ->
                val text = Regex("text=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                val desc = Regex("content-desc=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                val b = Regex("bounds=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                val pkg = Regex("package=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                println("WING025-UI   [$pkg] text='${text.take(80)}' desc='${desc.take(40)}' $b")
            }
    }

    /**
     * A freshly booted CI emulator can show a system "<some app> isn't responding" dialog (seen:
     * "Pixel Launcher isn't responding") over everything, hiding the app under test. Only a dialog
     * owned by the system (`android` package) about ANOTHER app is dismissed, with "Wait" (never
     * "Close app"). An ANR of Wingman itself is not hidden: the test fails on it.
     */
    fun dismissSystemAnrDialog() {
        repeat(3) {
            val title = device.findObject(By.pkg("android").textContains("isn't responding")) ?: return
            if (title.text.startsWith("Wingman")) {
                dumpHierarchy("Wingman ANR")
                throw AssertionError("the app under test is not responding: ${title.text}")
            }
            println("WING025-UI dismissing system dialog: ${title.text} (Wait)")
            device.findObject(By.pkg("android").text("Wait"))?.click() ?: return
            device.wait(Until.gone(By.pkg("android").textContains("isn't responding")), 5_000)
        }
    }

    /** Hides the soft keyboard if it is showing (it covers the bottom bar and bottom buttons). */
    fun hideKeyboard() {
        val shown = runCatching { device.executeShellCommand("dumpsys input_method") }.getOrDefault("")
            .lineSequence().any { it.contains("mInputShown=true") }
        if (shown) {
            device.pressBack()
            device.waitForIdle()
        }
    }

    fun waitText(text: String, timeoutMs: Long = this.timeoutMs) = waitFor(By.text(text), "\"$text\"", timeoutMs)

    fun click(sel: BySelector, what: String) {
        waitFor(sel, what).click()
        device.waitForIdle()
    }

    fun clickText(text: String) = click(By.text(text), "\"$text\"")

    private fun bounds(o: UiObject2): Rect = o.visibleBounds

    /** The top-most / bottom-most of several matches (a tab vs. a button with the same label). */
    fun clickTopMost(sel: BySelector, what: String) {
        waitFor(sel, what)
        device.findObjects(sel).minBy { bounds(it).top }.click()
        device.waitForIdle()
    }

    fun clickBottomMost(sel: BySelector, what: String) {
        waitFor(sel, what)
        device.findObjects(sel).maxBy { bounds(it).top }.click()
        device.waitForIdle()
    }

    /**
     * Bottom navigation item. Its label is a plain Text node (the clickable parent has no text),
     * so match the exact label and tap the bottom-most one: a click on the label lands on the item.
     */
    fun openTab(label: String) = clickBottomMost(By.text(label), "bottom nav \"$label\"")

    /** A TabRow tab: the top-most node with exactly [title] (a tab's label may also be on a button). */
    fun openInnerTab(title: String) = clickTopMost(By.text(title), "tab \"$title\"")

    /**
     * Scrolls the largest scrollable container until [sel] is on screen (down, then back up).
     *
     * Slow, settled steps: a fast UiAutomator swipe ends with a fling in Compose, which can carry a
     * short node past the viewport between two checks, so each step is half a page at [SCROLL_SPEED]
     * and waits for the UI to go idle before looking. This only FINDS a node that already exists; to
     * wait for something that appears later (a status after a network call), use [waitUntilShown].
     */
    fun scrollTo(sel: BySelector, what: String, maxSwipes: Int = 25): UiObject2 {
        device.waitForIdle()
        device.findObject(sel)?.let { return it }
        // The screen may still be loading (only the bottom bar is drawn): wait, condition-based,
        // until the target or a scrollable container is there before searching.
        val ready = By.scrollable(true)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !device.hasObject(ready)) {
            device.findObject(sel)?.let { return it }
            dismissSystemAnrDialog()
            device.wait(Until.hasObject(ready), 1_000)
        }
        device.findObject(sel)?.let { return it }
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            for (i in 0 until maxSwipes) {
                val scrollable = device.findObjects(By.scrollable(true)).maxByOrNull { bounds(it).height() } ?: break
                val more = scrollable.scroll(direction, 0.5f, SCROLL_SPEED)
                device.waitForIdle()
                device.findObject(sel)?.let { return it }
                if (!more) break // reached the end in this direction
            }
        }
        return device.findObject(sel) ?: run {
            dumpHierarchy("could not scroll to $what")
            throw AssertionError("could not scroll to $what")
        }
    }

    /**
     * Waits (condition-based, up to [timeoutMs]) until [expected] is shown, keeping the always-present
     * [anchor] (e.g. a status line found by its test tag) on screen: content loading above it can
     * push it out of the viewport while we wait, so it is scrolled back into view between waits.
     */
    fun waitUntilShown(anchor: BySelector, expected: BySelector, what: String, timeoutMs: Long = this.timeoutMs): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dismissSystemAnrDialog()
            // The anchor's section may still be loading; keep waiting until the deadline.
            runCatching { scrollTo(anchor, "anchor of $what") }
            if (device.wait(Until.hasObject(expected), 2_000)) device.findObject(expected)?.let { return it }
        }
        dumpHierarchy("never showed $what")
        throw AssertionError("UI never showed $what within ${timeoutMs}ms")
    }

    /** Taps the top-most "Refresh" until [sel] shows up (screens re-read the server on request). */
    fun refreshUntil(sel: BySelector, what: String, tries: Int = 10, stepMs: Long = 3_000) {
        repeat(tries) {
            if (device.wait(Until.hasObject(sel), stepMs)) return
            device.findObjects(By.text("Refresh")).minByOrNull { bounds(it).top }?.click()
        }
        waitFor(sel, what, stepMs)
    }

    /** Taps the first [text] node below the node [anchor] (e.g. a dialog's button below its title). */
    fun clickBelow(anchor: String, text: String) {
        val top = bounds(waitText(anchor)).bottom
        val target = device.findObjects(By.text(text)).filter { bounds(it).top >= top }.minByOrNull { bounds(it).top }
            ?: run {
                dumpHierarchy("no \"$text\" below \"$anchor\"")
                throw AssertionError("no \"$text\" below \"$anchor\"")
            }
        target.click()
        device.waitForIdle()
    }

    /** The Switch whose row is the label [labelText] (closest vertical center). */
    fun switchBeside(labelText: String): UiObject2 {
        val label = waitText(labelText)
        val cy = bounds(label).centerY()
        return device.findObjects(By.checkable(true)).minByOrNull { kotlin.math.abs(bounds(it).centerY() - cy) }
            ?: throw AssertionError("no switch beside \"$labelText\"")
    }

    /** Debug-only "Developer sign-in" panel: the only text field on the sign-in screen. */
    fun signInWithDevToken(token: String) {
        dismissSystemAnrDialog()
        device.wait(Until.hasObject(By.text("Use dev token")), timeoutMs)
        dismissSystemAnrDialog()
        device.wait(Until.hasObject(By.text("Use dev token")), 5_000)
        if (!has(By.text("Use dev token"))) {
            // Every E2E phase starts with `pm clear`, so the app must be signed out. Never assume
            // "already signed in" silently: say what is on screen.
            dumpHierarchy("no dev sign-in screen")
            throw AssertionError("the dev sign-in screen never showed (expected after pm clear)")
        }
        // setText through accessibility (no tap): no soft keyboard pops up over the screen.
        val field = waitFor(By.clazz("android.widget.EditText"), "dev token field")
        field.text = token
        hideKeyboard()
        clickText("Use dev token")
        // The shell (bottom navigation) replaces the sign-in screen once the token is stored.
        device.wait(Until.gone(By.text("Use dev token")), timeoutMs)
        hideKeyboard()
    }
}

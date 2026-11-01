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

class E2eDevice(private val timeoutMs: Long = UI_TIMEOUT_MS) {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun has(sel: BySelector): Boolean = device.hasObject(sel)

    fun waitFor(sel: BySelector, what: String, timeoutMs: Long = this.timeoutMs): UiObject2 {
        if (!device.wait(Until.hasObject(sel), timeoutMs / 2)) {
            hideKeyboard() // the soft keyboard can cover bottom bars and buttons
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

    /** Scrolls the largest scrollable container until [sel] is on screen (down, then back up). */
    fun scrollTo(sel: BySelector, what: String, maxSwipes: Int = 15): UiObject2 {
        device.findObject(sel)?.let { return it }
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(maxSwipes) {
                val scrollable = device.findObjects(By.scrollable(true)).maxByOrNull { bounds(it).height() } ?: return@repeat
                val moved = scrollable.scroll(direction, 0.7f)
                device.findObject(sel)?.let { return it }
                if (!moved) return@repeat
            }
        }
        return device.findObject(sel) ?: throw AssertionError("could not scroll to $what")
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
        device.wait(Until.hasObject(By.text("Use dev token")), timeoutMs)
        if (!has(By.text("Use dev token"))) return // already signed in
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

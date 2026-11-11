package com.wingman.integrations.screenreader.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.wingman.integrations.screenreader.ScreenReaderCore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/*
 * THIN ANDROID ADAPTER (D-032, experimental build only). Compiled in CI; never run against a real
 * dating app: every device behaviour is UNVERIFIED.
 *
 * A READ-ONLY accessibility service. The system binds it only after the user switches it on in
 * Android's Accessibility settings. Its configuration (res/xml/wingman_screen_reader.xml) asks for
 * window content of three packages and two event types, nothing else. This class:
 *  - drops an event unless the pure-Kotlin gate says the package is allowlisted AND switched on by
 *    the user in Wingman, before any window content is looked at;
 *  - copies the active window's node tree into a snapshot and hands it to ScreenReaderCore;
 *  - never acts on another app in any way: it has no code path that clicks, types, scrolls,
 *    gestures or presses a system button (a JVM test scans this package for those calls);
 *  - never logs anything it read.
 */
class WingmanScreenReaderService : AccessibilityService() {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wingman-screen-reader").apply { isDaemon = true } }
    @Volatile private var lastReadAt = 0L
    @Volatile private var trailing: ScheduledFuture<*>? = null
    @Volatile private var idle: ScheduledFuture<*>? = null

    override fun onServiceConnected() {
        ScreenReaderRuntime.core(this) // builds the core and registers the capability source
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        // The allowlist and the user's toggle, checked before any window content is touched.
        if (!ScreenReaderRuntime.core(this).mode(packageName).reads) return
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastReadAt)
        if (wait <= 0) {
            runCatching { worker.execute { readActiveWindow() } }
        } else if (trailing?.isDone != false) {
            // One trailing read after a burst of events, so the settled screen is not missed.
            trailing = runCatching { worker.schedule({ readActiveWindow() }, wait, TimeUnit.MILLISECONDS) }.getOrNull()
        }
    }

    private fun readActiveWindow() {
        lastReadAt = SystemClock.uptimeMillis()
        val core = ScreenReaderRuntime.core(this)
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        // The window that is active NOW decides, not the event that woke us up.
        val packageName = root.packageName?.toString() ?: return
        val mode = core.mode(packageName)
        if (!mode.reads) return
        val snapshot = runCatching {
            AccessibilityNodeReader.snapshot(root, packageName, System.currentTimeMillis(), readText = mode.capture)
        }.getOrNull() ?: return
        runCatching { core.onSnapshot(snapshot) }
        idle?.cancel(false)
        idle = runCatching {
            worker.schedule({ runCatching { core.onIdle() } }, ScreenReaderCore.IDLE_FINISH_MS + 500, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // Switched off in Android settings: finish what was read, then stop.
        runCatching { worker.execute { runCatching { ScreenReaderRuntime.core(this).flush() } } }
        worker.shutdown()
        return super.onUnbind(intent)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 350L
    }
}

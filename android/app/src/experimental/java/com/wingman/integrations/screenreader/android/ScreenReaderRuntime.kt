package com.wingman.integrations.screenreader.android

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.wingman.core.api.WingmanApi
import com.wingman.integrations.notifications.CapabilityState
import com.wingman.integrations.notifications.CapabilityStatus
import com.wingman.integrations.notifications.FileNotificationStateStore
import com.wingman.integrations.notifications.ProfileReadCapability
import com.wingman.integrations.notifications.ProfileReadCapabilitySource
import com.wingman.integrations.notifications.android.NotificationSyncRuntime
import com.wingman.integrations.screenreader.CapturedProfileStore
import com.wingman.integrations.screenreader.InspectorLog
import com.wingman.integrations.screenreader.ProfileSync
import com.wingman.integrations.screenreader.ScreenReaderApps
import com.wingman.integrations.screenreader.ScreenReaderCore
import com.wingman.integrations.screenreader.ScreenReaderLog
import com.wingman.integrations.screenreader.ScreenReaderSettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors

/*
 * THIN ANDROID ADAPTER (D-032, experimental build only). Compiled in CI; never run on a device.
 */

/**
 * Process-wide holder of the screen reader: the pure-Kotlin core over two app-private state files
 * (settings and captured profiles; the redacted inspector log), and the upload path once the app
 * has a backend client. In a cold process started only for the service there is no client: captures
 * are saved on the phone and uploaded the next time Wingman is opened.
 */
object ScreenReaderRuntime {
    const val LOG_TAG = "WingmanScreenReader"
    private const val STATE_FILE = "wingman-screen-reader.json"
    private const val INSPECTOR_FILE = "wingman-screen-inspector.json"
    const val PROFILE_READ_NOTE = "Experimental read-only screen reader; profile text as shown on screen. Not verified on a device."

    @Volatile private var core: ScreenReaderCore? = null
    @Volatile private var sync: ProfileSync? = null
    @Volatile private var appContext: Context? = null
    private val background = Executors.newSingleThreadExecutor { r -> Thread(r, "wingman-screen-reader-sync").apply { isDaemon = true } }

    /** Redacted: event names, counts and kinds only. The core never passes screen text. */
    private val androidLog = ScreenReaderLog { event, fields ->
        Log.i(LOG_TAG, event + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    @Synchronized
    fun core(context: Context): ScreenReaderCore {
        core?.let { return it }
        val app = context.applicationContext
        appContext = app
        val state = FileNotificationStateStore(File(app.filesDir, STATE_FILE))
        val settings = ScreenReaderSettings(state)
        val built = ScreenReaderCore(
            settings = settings,
            inspector = InspectorLog(FileNotificationStateStore(File(app.filesDir, INSPECTOR_FILE))),
            profiles = CapturedProfileStore(state),
            log = androidLog,
            conversationNames = { platform -> sync?.knownConversationNames(platform) ?: emptyList() },
            onCaptured = { profile -> uploadSoon(profile.localId) },
        )
        // D-032: while Hinge capture is on, the Hinge connection's profile_read is UNVERIFIED
        // (never VERIFIED from here); otherwise the notification path's UNSUPPORTED stands.
        ProfileReadCapability.source = ProfileReadCapabilitySource { platform ->
            val pkg = ScreenReaderApps.CAPTURE.firstOrNull { ScreenReaderApps.platformOf(it) == platform }
            if (pkg != null && settings.captureEnabled(pkg)) CapabilityState(CapabilityStatus.UNVERIFIED, PROFILE_READ_NOTE) else null
        }
        core = built
        return built
    }

    /** Called once from the app's start-up (ExperimentalModules.init). Works without any Hinge build property. */
    @Synchronized
    fun init(context: Context, api: WingmanApi) {
        val c = core(context)
        val app = context.applicationContext
        sync = ProfileSync(
            api = api,
            store = c.profiles,
            connectionId = { platform -> connectionId(app, platform) },
            log = androidLog,
        )
        syncSoon()
    }

    /** The upload path, or null before the app has started (service-only process). */
    fun sync(): ProfileSync? = sync

    /**
     * The paired connection profiles of [platform] are uploaded with: the app's notification
     * connection (Settings > Dating apps), whose capability document carries `profile_read`.
     */
    private fun connectionId(app: Context, platform: String): String? =
        runCatching { NotificationSyncRuntime.host(app).pairingState(platform) }.getOrNull()?.takeIf { it.isPaired }?.id

    /** Whether the user has switched Wingman's service on in Android's Accessibility settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val mine = ComponentName(context, WingmanScreenReaderService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    /**
     * The per-app profile toggle. Off: reading stops at once (the gate refuses the next event), the
     * viewing in progress is thrown away, and the connection's profile_read downgrade is reported.
     */
    fun setCaptureEnabled(context: Context, packageName: String, enabled: Boolean): Boolean {
        val c = core(context)
        val ok = c.settings.setCaptureEnabled(packageName, enabled)
        if (!enabled) c.stop(packageName)
        if (ok) reportProfileRead(context, packageName, if (enabled) "screen_reader_on" else "screen_reader_off")
        return ok
    }

    fun setInspectorEnabled(context: Context, packageName: String, enabled: Boolean): Boolean =
        core(context).settings.setInspectorEnabled(packageName, enabled)

    fun setExplainerAccepted(context: Context, accepted: Boolean) {
        val c = core(context)
        c.settings.explainerAccepted = accepted
        if (!accepted) {
            for (pkg in ScreenReaderApps.CAPTURE) {
                c.stop(pkg)
                reportProfileRead(context, pkg, "screen_reader_off")
            }
        }
    }

    private fun reportProfileRead(context: Context, packageName: String, reason: String) {
        val platform = ScreenReaderApps.platformOf(packageName) ?: return
        val app = context.applicationContext
        NotificationSyncRuntime.runInBackground {
            val host = NotificationSyncRuntime.host(app)
            if (host.reportCapabilities(platform, reason)) host.sync()
        }
    }

    /** Runs [block] off the main thread (uploads, links and deletes are network calls). */
    fun runInBackground(block: suspend () -> Unit) = background.execute { runCatching { runBlocking { block() } } }

    fun syncSoon() = runInBackground { sync?.syncAll() }

    private fun uploadSoon(localId: String) = runInBackground { sync?.upload(localId) }
}

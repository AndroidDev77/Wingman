package com.wingman.integrations.hinge.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wingman.AppInfo
import com.wingman.core.auth.StaticTokenProvider
import com.wingman.core.auth.android.KeystoreSecretCipher
import com.wingman.core.di.AppContainer
import com.wingman.integrations.hinge.EncryptedFileHingeSessionStore
import com.wingman.integrations.hinge.HingeBuildFlags
import com.wingman.integrations.hinge.HingeConnector
import com.wingman.integrations.hinge.HingeLog
import com.wingman.integrations.hinge.HingeSecretCipher
import com.wingman.integrations.hinge.HingeStopReason
import com.wingman.integrations.hinge.HttpHingeBackend
import com.wingman.integrations.hinge.notifications.HingeNotificationPlugin
import com.wingman.integrations.notifications.android.NotificationConnectorRuntime
import com.wingman.integrations.sms.FileSmsStateStore
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * THIN ANDROID ADAPTER (WING-029). Compiled in CI; never run on a device (I-003). Nothing here has
 * contacted Hinge or Sendbird.
 */

/**
 * Process-wide entry point of the Hinge module. The integrator wires ONE line in
 * `MainActivity.onCreate`, after the container is built and before anything builds the
 * notification core:
 *
 *     HingeModule.init(applicationContext, container)
 *
 * - Always: registers [HingeNotificationPlugin] with the WING-017 runtime (the notification-assisted
 *   fallback ships regardless, D-021 item 7). It merges with plugins other modules registered.
 * - Only when `BuildConfig.WINGMAN_EXPERIMENTAL` is true AND the client identity is configured:
 *   builds the experimental [HingeConnector] and, if the user is opted in and paired, schedules
 *   the 15-minute worker. Otherwise the connector is never built and nothing runs (inert).
 */
object HingeModule {
    private const val TAG = "WingmanHinge"
    const val WORK_PERIODIC = "wingman-hinge-sync"
    const val WORK_ONCE = "wingman-hinge-finish"
    private const val STATE_FILE = "wingman-hinge-state.json"
    private const val SESSION_FILE = "wingman-hinge-session.bin"
    private const val KEY_ALIAS = "wingman.hinge.session.v1"
    private const val CHANNEL = "wingman_hinge_status"

    @Volatile private var connector: HingeConnector? = null
    private val background = Executors.newSingleThreadExecutor()

    /** Redacted: event names and fixed fields only (the connector never passes private values). */
    private val androidLog = HingeLog { event, fields -> Log.i(TAG, event + " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }) }

    @Synchronized
    fun init(context: Context, container: AppContainer) {
        installNotificationPlugin()
        val flags = HingeBuildFlags.fromBuildConfig()
        if (!flags.connectorAvailable) return // inert: no connector, no worker, no screen
        val app = context.applicationContext
        if (connector == null) {
            val dir = app.noBackupFilesDir
            connector = HingeConnector(
                flags = flags,
                store = FileSmsStateStore(File(dir, STATE_FILE)),
                sessions = EncryptedFileHingeSessionStore(File(dir, SESSION_FILE), KeystoreHingeCipher(KEY_ALIAS)),
                backend = HttpHingeBackend(
                    container.api,
                    AppInfo.normalizeBaseUrl(container.config.apiBaseUrl),
                    container.auth ?: StaticTokenProvider { null },
                ),
                log = androidLog,
                onStopped = { reason -> notifyStopped(app, reason) },
                cancelWork = { WorkManager.getInstance(app).cancelUniqueWork(WORK_PERIODIC) },
            )
        }
        background.execute {
            runCatching {
                val c = connector ?: return@runCatching
                val s = c.status()
                if (s.revokePending || s.queuedUploads > 0) scheduleFinish(app)
                if (s.optedIn && s.paired && s.stop == null) schedulePeriodic(app)
            }
        }
    }

    /** The experimental connector, or null when the build or configuration does not allow it. */
    fun connector(): HingeConnector? = connector

    /** Adds the Hinge plugin to whatever plugins are already registered (idempotent). */
    @Synchronized
    fun installNotificationPlugin() {
        val registry = NotificationConnectorRuntime.registry()
        if (registry.forPlatform("hinge") != null) return
        val existing = registry.platforms.mapNotNull { registry.forPlatform(it) }
        runCatching { NotificationConnectorRuntime.installPlugins(existing + HingeNotificationPlugin()) }
            .onFailure { Log.w(TAG, "hinge.plugin.install_failed " + it.javaClass.simpleName) }
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<HingeSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleFinish(context: Context) {
        val request = OneTimeWorkRequestBuilder<HingeSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.KEEP, request)
    }

    /** In-app state already shows the reason; this adds a system notification when allowed. */
    private fun notifyStopped(context: Context, reason: HingeStopReason) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hinge (experimental)", NotificationManager.IMPORTANCE_DEFAULT))
            val n = android.app.Notification.Builder(context, CHANNEL) // minSdk 26
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Hinge connector stopped")
                .setContentText(reason.userMessage)
                .setStyle(android.app.Notification.BigTextStyle().bigText(reason.userMessage))
                .setAutoCancel(true)
                .build()
            nm.notify(CHANNEL.hashCode(), n)
        }
    }
}

/** The 15-minute job: Wingman leftovers always; Hinge sync and send only while active. */
class HingeSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val c = HingeModule.connector() ?: return Result.success()
        runCatching { c.runPeriodic() }
        return Result.success() // never a WorkManager retry loop: the connector has its own limits
    }
}

/** [HingeSecretCipher] over the existing Keystore AES-256-GCM cipher, with its own key alias. */
class KeystoreHingeCipher(private val alias: String) : HingeSecretCipher {
    private val delegate = KeystoreSecretCipher(alias)
    override fun encrypt(plain: ByteArray): ByteArray = delegate.encrypt(plain)
    override fun decrypt(sealed: ByteArray): ByteArray = delegate.decrypt(sealed)
    override fun destroyKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}

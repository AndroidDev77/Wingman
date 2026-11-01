package com.wingman.integrations.sms.android

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wingman.BuildConfig
import com.wingman.core.di.AppConfig
import com.wingman.core.di.AppContainer
import com.wingman.core.di.AppContainerProvider
import com.wingman.integrations.sms.SmsAddress
import com.wingman.integrations.sms.SmsCapabilities
import com.wingman.integrations.sms.SmsConnectionScope
import com.wingman.integrations.sms.SmsSendCommand
import java.util.UUID

/*
 * DEBUG-ONLY DEVICE HARNESS (WING-016). UNVERIFIED: compiled only in CI, never run on a device.
 *
 * Purpose: let a human run the device checklist in docs/integrations/android_sms.md. Steps 4/5
 * send directly: the user's tap on "Send" is the authorization; the record stores authorized_by =
 * "device-demo:manual-tap". Steps 7/8 run the real contract v1.1.0 round trip instead: upload
 * envelopes, claim leased instructions and execute them through the same SmsSender. It must only
 * be declared in the DEBUG manifest (src/debug), and it refuses to run in a non-debuggable build. Plain framework views, so it needs no new dependency.
 *
 * Private text is shown on this screen (it is the device owner's own data) and never logged.
 */
class SmsDemoActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var to: EditText
    private lateinit var body: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            finish()
            return
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }.also(col::addView)
        status = TextView(this).also(col::addView)
        button("1. Request default-SMS-app role") { SmsRoleHelper.requestRoleIntent(this)?.let { startActivityForResult(it, 1) } ?: refresh("Role already held or unavailable") }
        button("2. Request SMS permissions") {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS), 2)
        }
        button("3. Configure demo connection scope (offline only; real pairing is Settings > Phone SMS)") {
            // Fixed demo IDs, same as contracts/examples. Against a real backend, pair from Settings instead
            // (I-020): a scope set here is checked with getConnection on the next app start and cleared on 404.
            SmsConnectorRuntime.configure(this, SmsConnectionScope(DEMO_USER, DEMO_CONNECTION))
            refresh("Configured demo scope")
        }
        to = EditText(this).apply { hint = "Destination number, e.g. +15550101234"; inputType = InputType.TYPE_CLASS_PHONE }.also(col::addView)
        body = EditText(this).apply { hint = "Message text" }.also(col::addView)
        button("4. Send authorized SMS (NEW key)") { send(newKey = true) }
        button("5. Send again with the SAME key, also after a process kill (must not resend)") { send(newKey = false) }
        button("Re-inject last received SMS (duplicate / post-restart redelivery)") {
            val connector = SmsConnectorRuntime.get(this) ?: return@button refresh("Configure the scope first (step 3)")
            val sms = SmsConnectorRuntime.debugLastInbound(this) ?: return@button refresh("No SMS received yet")
            val before = connector.outbox.size()
            val outcome = connector.inbound.onSmsReceived(sms)
            refresh("re-inject ${outcome.externalMessageId}: ${outcome::class.simpleName}; outbox $before -> ${connector.outbox.size()}")
        }
        button("6. Run maintenance (expire stale sends, try upload)") {
            val report = SmsConnectorRuntime.get(this)?.maintain()
            refresh("maintain: $report")
        }
        button("7. Connect backend (AppContainer.api: mock or HTTP per build)") {
            val container = AppContainerProvider.get {
                AppContainer(AppConfig(apiBaseUrl = BuildConfig.API_BASE_URL, useMockApi = BuildConfig.USE_MOCK_API))
            }
            SmsConnectorRuntime.connectBackend(container.api)
            refresh("Backend connected: ${if (container.isMock) "MockWingmanApi (offline)" else BuildConfig.API_BASE_URL}")
        }
        button("8. Sync with backend now (upload, claim, execute, upload)") {
            refresh("sync running...")
            SmsConnectorRuntime.syncInBackground(this) { report -> runOnUiThread { refresh("sync: $report") } }
        }
        button("Refresh") { refresh(null) }
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 11f }.also(col::addView)
        setContentView(ScrollView(this).apply { addView(col) })
        refresh(null)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh(null)
    }

    private fun send(newKey: Boolean) {
        val connector = SmsConnectorRuntime.get(this) ?: return refresh("Configure the scope first (step 3)")
        val lastCommand = SmsConnectorRuntime.debugLastCommand(this)
        val command = if (newKey || lastCommand == null) {
            val dest = runCatching { SmsAddress.normalize(to.text.toString()) }.getOrNull() ?: return refresh("Enter a destination")
            val id = UUID.randomUUID().toString()
            SmsSendCommand(id, "send:$id", SmsAddress.threadIdFor(dest), body.text.toString().ifEmpty { "Wingman WING-016 device test" }, "device-demo:manual-tap")
        } else {
            lastCommand
        }
        SmsConnectorRuntime.rememberDebugCommand(this, command)
        val outcome = connector.sender.send(command, SmsRoleHelper.sendPreconditions(this))
        refresh("send(${command.idempotencyKey}): ${outcome::class.simpleName}, replayed=${outcome.replayed}, " +
            "state=${outcome.record.state}, transportAttempts=${connector.sender.transportAttempts(command.idempotencyKey)}")
    }

    private fun refresh(message: String?) {
        val device = SmsRoleHelper.deviceState(this)
        val connector = SmsConnectorRuntime.get(this)
        status.text = buildString {
            appendLine(message ?: "")
            appendLine("default SMS app: ${device.isDefaultSmsApp}; RECEIVE ${device.receiveSmsGranted}; SEND ${device.sendSmsGranted}; READ ${device.readSmsGranted}")
            val local = SmsConnectorRuntime.host(this@SmsDemoActivity).localStatus()
            appendLine("paired (scope configured): ${connector != null}; connection: ${connector?.scope?.connectionId}; outbox: ${local.pendingEnvelopes}; dead letters: ${local.deadLetters}")
            appendLine("backend: ${connector?.poller != null}; upload backoff until: ${connector?.uploadBackoff?.nextAttemptAtMillis()}")
            connector?.poller?.let { appendLine("device_install_id: ${it.deviceInstallId()}; pending claim key: ${it.pendingClaimKey()}") }
        }
        output.text = buildString {
            appendLine("capabilities:")
            appendLine(SmsCapabilities.build(device, System.currentTimeMillis()).toString())
            appendLine()
            appendLine("pending envelopes (oldest first):")
            connector?.outbox?.pending()?.forEach { appendLine(it.toString()); appendLine() }
        }
    }

    private companion object {
        const val DEMO_USER = "0192f3a0-0000-7000-8000-000000000001"
        const val DEMO_CONNECTION = "0192f3a0-0000-7000-8000-0000000000a1"
    }
}

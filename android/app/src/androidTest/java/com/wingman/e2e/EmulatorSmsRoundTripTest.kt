package com.wingman.e2e

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.wingman.BuildConfig
import com.wingman.MainActivity
import com.wingman.features.connections.SmsConnectionCopy
import com.wingman.integrations.sms.SmsHostSyncReport
import com.wingman.integrations.sms.SmsInstallId
import com.wingman.integrations.sms.android.SmsConnectorRuntime
import com.wingman.integrations.sms.android.SmsRoleHelper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WING-025 real-channel attempt, **"emulator SMS stack exercised"**. This is NOT a physical device
 * and NOT a carrier: the emulator's modem is simulated. A pass here does not make SMS VERIFIED;
 * G5 stays blocked on I-003 (docs/e2e_results.md).
 *
 * The CI job prepares the emulator before this test (scripts/e2e/emulator-sms.sh): grants the
 * default-SMS role with `cmd role add-role-holder android.app.role.SMS com.wingman` and the SMS
 * permissions with `pm grant`. Then, as a user and the device would:
 *
 *   1. sign in (dev token) and pair this phone's SMS from Settings > Connections (the app's own
 *      pairing flow: createConnection with this install's device_install_id);
 *   2. the host side sends an SMS INTO the emulator with `adb emu sms send` (step driven by the
 *      CI script, which waits for the marker file written here); the app's SMS_DELIVER receiver
 *      records it and a sync uploads it; the backend has the inbound message;
 *   3. the user approves the reply in Approvals; a sync claims the leased instruction and hands it
 *      to the emulator's SmsManager; the emulator reports the per-part "sent" result; the next sync
 *      uploads `message.send_acknowledged` and the server reports `sent`.
 *
 * Every observation is printed with the prefix `WING025-SMS` so the CI log is the evidence.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorSmsRoundTripTest {

    private val backend = E2eBackend()
    private val ui = E2eDevice()
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun log(msg: String) = println("WING025-SMS $msg")

    /** One blocking sync round on the app's own runtime (the same call MainActivity makes). */
    private fun sync(): SmsHostSyncReport? {
        val latch = CountDownLatch(1)
        var out: SmsHostSyncReport? = null
        SmsConnectorRuntime.syncInBackground(ctx) { r -> out = r; latch.countDown() }
        latch.await(60, TimeUnit.SECONDS)
        log("sync -> ${out?.let(SmsConnectorRuntimeSummary::of)}")
        return out
    }

    @Test fun emulatorSmsRoundTrip() {
        assumeTrue("set -e wingman.sms true to run the emulator SMS round trip", args.getString("wingman.sms") == "true")
        assertFalse(BuildConfig.USE_MOCK_API)
        val device = SmsRoleHelper.deviceState(ctx)
        log("device state: telephony=${device.hasTelephony} role=${device.isDefaultSmsApp} receive=${device.receiveSmsGranted} send=${device.sendSmsGranted} default_sms_package=${android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)}")
        assertTrue("emulator must report telephony", device.hasTelephony)
        assertTrue("CI must grant the default-SMS role first (cmd role add-role-holder)", device.isDefaultSmsApp)
        assertTrue("CI must grant RECEIVE_SMS/SEND_SMS first (pm grant)", device.receiveSmsGranted && device.sendSmsGranted)

        backend.me()
        backend.putAssisted() // this test is about the SMS channel, not onboarding
        ActivityScenario.launch(MainActivity::class.java).use { roundTrip() }
    }

    private fun roundTrip() {
        ui.signInWithDevToken(E2eArgs.token)

        // 1. Pair from Settings > Connections > Phone SMS (the app's pairing flow).
        ui.openTab("Settings")
        // The SMS card's status line has a stable test tag and is present in every state: keep it
        // in view and wait on it (condition-based) for the pair button, then for Connected after
        // the pairing call, instead of searching the page once by scrolling.
        val status = By.res(SmsConnectionCopy.STATUS_TEST_TAG)
        ui.waitUntilShown(status, By.text("Pair this phone's SMS"), "pair button", timeoutMs = 60_000).click()
        ui.device.waitForIdle()
        ui.waitUntilShown(status, status.text("Status: Connected"), "SMS status Connected", timeoutMs = 60_000)
        ui.scrollTo(By.text("Send replies: UNVERIFIED (not yet tested on a device)"), "send UNVERIFIED")
        ui.scrollTo(By.text("Autopilot: not available for SMS"), "autopilot not available")
        val install = SmsInstallId.getOrCreate(SmsConnectorRuntime.host(ctx).store)
        val conn = eventually(what = "paired sms connection") {
            backend.connections().firstOrNull { it.getString("platform") == "sms" && it.isNull("unpaired_at") }
        }
        assertEquals(install, conn.getString("device_install_id"))
        assertEquals("UNVERIFIED", conn.getJSONObject("capabilities").getJSONObject("capabilities").getJSONObject("send").getString("status"))
        log("paired connection=${conn.getString("id")} install=$install send=UNVERIFIED")

        // 2. Tell the host script we are ready for `adb emu sms send`, then wait for the SMS.
        val number = args.getString("wingman.smsFrom") ?: "5551234567"
        val text = args.getString("wingman.smsText") ?: "WING025-emulator-hello"
        File(ctx.filesDir, "wing025-ready").writeText("ready")
        log("READY_FOR_SMS (waiting for adb emu sms send $number ...)")
        val conv = eventually(timeoutMs = 240_000, intervalMs = 2_000, what = "inbound SMS ingested by the backend") {
            sync()
            backend.conversations().let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                .firstOrNull { it.getString("platform") == "sms" }
        }
        val conversationId = conv.getString("id")
        val inbound = backend.messages(conversationId).first { it.getString("direction") == "inbound" }
        log("inbound ingested: conversation=$conversationId thread=${conv.optString("external_thread_id")} text_matches=${inbound.getString("text") == text}")
        assertEquals(text, inbound.getString("text"))

        // 3. Approve the reply in the app.
        val approval: JSONObject = eventually(what = "pending approval") { backend.approvals("pending", conversationId).firstOrNull() }
        val reasons = approval.getJSONArray("reasons").let { r -> (0 until r.length()).map { r.getString(it) } }
        log("approval reasons=$reasons")
        assertTrue("SMS send is UNVERIFIED, so approval is required", "capability_unverified" in reasons)
        ui.openTab("Approvals")
        ui.refreshUntil(By.textContains("[dev mock]"), "the draft in the approval queue")
        ui.waitText("Nothing is sent until you approve.")
        ui.clickText("Approve")
        ui.waitText("Approved. It shows as sent only after the messaging app confirms it.")

        // Claim + SmsManager submit, then upload the "sent" result.
        val sent = eventually(timeoutMs = 120_000, intervalMs = 3_000, what = "server reports the SMS reply sent") {
            sync()
            backend.messages(conversationId).firstOrNull {
                it.getString("direction") == "outbound" && it.getString("delivery_state") in setOf("sent", "delivered")
            }
        }
        log("outbound state=${sent.getString("delivery_state")} external_message_id=${sent.optString("external_message_id")} attempt=${sent.getInt("attempt_count")}")
        val types = backend.actions(conversationId = conversationId).map { it.getString("type") }
        log("audit=$types")
        assertTrue(types.containsAll(listOf("message_received", "approval_approved", "send_queued", "send_attempted", "send_acknowledged")))
        File(ctx.filesDir, "wing025-done").writeText("done")
        log("RESULT emulator SMS stack exercised: inbound via adb emu sms -> SMS_DELIVER -> backend; reply approved -> claimed -> SmsManager -> sent ack -> backend 'sent'. NOT a physical device, NOT a carrier.")
    }
}

/** Counts-only summary of a sync report for the log (never text or numbers). */
object SmsConnectorRuntimeSummary {
    fun of(r: SmsHostSyncReport): String = com.wingman.integrations.sms.SmsConnectorHost.summarize(r)
}

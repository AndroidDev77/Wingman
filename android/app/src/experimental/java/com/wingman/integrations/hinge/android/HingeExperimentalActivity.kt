package com.wingman.integrations.hinge.android

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wingman.integrations.hinge.HingeConnector
import com.wingman.integrations.hinge.HingeConsentText
import com.wingman.integrations.hinge.HingeLoginState
import com.wingman.integrations.hinge.HingeLoginStep
import com.wingman.integrations.hinge.HingePairResult
import java.util.concurrent.Executors

/*
 * THIN ANDROID ADAPTER (WING-029). Compiled in CI; never run on a device (I-003).
 *
 * The opt-in screen of the experimental Hinge connector (matrix §6b): warning first, explicit
 * acknowledgement, phone number, the code(s) the user types, pairing with Wingman, the stop
 * reason with a manual re-enable, and "Turn off". Plain framework views (no new dependency), so a
 * Compose screen in Settings > Connections can replace it later. Exists only when the module is
 * available (experimental build); otherwise it shows one line and finishes nothing.
 *
 * FLAG_SECURE keeps the phone number and codes out of screenshots and the recents thumbnail.
 * Nothing typed here is logged or kept after the step completes.
 */
class HingeExperimentalActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var consentBox: LinearLayout
    private lateinit var loginBox: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var pairButton: Button
    private lateinit var reEnableButton: Button
    private lateinit var optOutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        setContentView(ScrollView(this).apply { addView(col) })
        val c = HingeModule.connector()
        if (c == null) {
            col.addView(TextView(this).apply { text = "The experimental Hinge connector is not part of this build." })
            return
        }

        consentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(col::addView)
        consentBox.addView(TextView(this).apply { text = HingeConsentText.TITLE; textSize = 20f })
        HingeConsentText.BODY.forEach { p -> consentBox.addView(TextView(this).apply { text = "• $p"; setPadding(0, 16, 0, 0) }) }
        val ack = CheckBox(this).apply { text = HingeConsentText.ACKNOWLEDGE_LABEL }.also(consentBox::addView)
        val accept = Button(this).apply { text = HingeConsentText.ACCEPT_BUTTON; isEnabled = false }.also(consentBox::addView)
        ack.setOnCheckedChangeListener { _, checked -> accept.isEnabled = checked }
        accept.setOnClickListener { act { c.acceptRisk(ack.isChecked); "Accepted." } }

        loginBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also(col::addView)
        phoneInput = EditText(this).apply {
            hint = "Phone number with country code, e.g. +15550100000"
            inputType = InputType.TYPE_CLASS_PHONE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }.also(loginBox::addView)
        Button(this).apply { text = "Hinge will text you a code: send it" }.also(loginBox::addView).setOnClickListener {
            val phone = phoneInput.text.toString()
            act { describe(c.requestCode(phone)) }
        }
        codeInput = EditText(this).apply {
            hint = "Code from Hinge"
            inputType = InputType.TYPE_CLASS_NUMBER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }.also(loginBox::addView)
        Button(this).apply { text = "Submit code" }.also(loginBox::addView).setOnClickListener {
            val code = codeInput.text.toString()
            codeInput.setText("")
            act {
                describe(if (c.auth.state is HingeLoginState.EmailCodeRequired) c.submitEmailCode(code) else c.submitCode(code))
            }
        }
        pairButton = Button(this).apply { text = "Connect to Wingman (approval-only)" }.also(col::addView)
        pairButton.setOnClickListener {
            act {
                when (val r = c.pair()) {
                    is HingePairResult.Paired -> {
                        HingeModule.schedulePeriodic(applicationContext)
                        "Connected (experimental, approval-only). Wingman will check for new messages about every 15 minutes. Every reply needs your approval."
                    }
                    is HingePairResult.Failed -> "Could not connect: ${r.code}"
                }
            }
        }
        status = TextView(this).apply { setPadding(0, 32, 0, 16) }.also(col::addView)
        reEnableButton = Button(this).apply { text = "Re-enable (I checked the official Hinge app)" }.also(col::addView)
        reEnableButton.setOnClickListener {
            act {
                if (c.reEnable()) {
                    HingeModule.schedulePeriodic(applicationContext)
                    "Re-enabled."
                } else {
                    "Not yet: wait until the cooldown ends."
                }
            }
        }
        optOutButton = Button(this).apply { text = "Turn off and delete my Hinge login from this phone" }.also(col::addView)
        optOutButton.setOnClickListener {
            act {
                val r = c.optOut()
                if (r.revokePending) HingeModule.scheduleFinish(applicationContext)
                "Turned off. Your Hinge login was deleted from this phone." + if (r.revokePending) " Wingman will be told when you are online." else ""
            }
        }
        refresh(null)
    }

    private fun describe(step: HingeLoginStep): String = when (step) {
        is HingeLoginStep.Moved -> when (val s = step.state) {
            is HingeLoginState.CodeRequested -> if (s.rejected) "That code was wrong or expired. Attempts left: ${s.attemptsLeft}." else "Hinge texted you a code. Type it here."
            is HingeLoginState.EmailCodeRequired -> if (s.rejected) "That email code was wrong or expired. Attempts left: ${s.attemptsLeft}." else "Hinge emailed you a code. Type it here."
            HingeLoginState.ChatReady -> "Logged in to Hinge. Now connect it to Wingman."
            HingeLoginState.Idle -> "Request a new code."
            is HingeLoginState.Blocked -> s.reason.userMessage
        }
        is HingeLoginStep.Failed -> "Not done: ${step.code}"
    }

    /** Runs [action] off the main thread, then shows its message and the new state. */
    private fun act(action: () -> String) {
        if (worker.isShutdown) return
        worker.execute {
            val message = runCatching(action).getOrElse { "Something went wrong (${it.javaClass.simpleName})." }
            runOnUiThread { if (!isDestroyed) refresh(message) }
        }
    }

    private fun refresh(message: String?) {
        val c = HingeModule.connector() ?: return
        if (worker.isShutdown) return
        worker.execute {
            val s = c.status()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                consentBox.visibility = if (s.optedIn) View.GONE else View.VISIBLE
                loginBox.visibility = if (s.optedIn && s.stop == null && s.login != HingeLoginState.ChatReady) View.VISIBLE else View.GONE
                pairButton.visibility = if (s.optedIn && s.login == HingeLoginState.ChatReady && !s.paired && s.stop == null) View.VISIBLE else View.GONE
                reEnableButton.visibility = if (s.stop != null) View.VISIBLE else View.GONE
                reEnableButton.isEnabled = s.canReEnable
                optOutButton.visibility = if (s.optedIn || s.paired) View.VISIBLE else View.GONE
                status.text = buildString {
                    message?.let { append(it).append("\n\n") }
                    append("Experimental, approval-only. ")
                    append(if (s.paired) "Connected to Wingman." else "Not connected to Wingman.")
                    s.notice?.let { append("\n\nStopped: ").append(it) }
                    append("\n\nReplies sent today: ${s.sendsToday} of 20. Hinge requests today: ${s.callsToday}.")
                    if (s.revokePending) append("\nWaiting to tell Wingman that you turned this off.")
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

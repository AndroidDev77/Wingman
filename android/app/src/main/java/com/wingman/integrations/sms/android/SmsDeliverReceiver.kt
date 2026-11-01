package com.wingman.integrations.sms.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.wingman.integrations.sms.IncomingSms
import com.wingman.integrations.sms.SmsInboundOutcome

/*
 * THIN ANDROID ADAPTER (WING-016). UNVERIFIED on device and not compiled in the authoring
 * environment. Manifest: exported, protected by android.permission.BROADCAST_SMS, filter
 * android.provider.Telephony.SMS_DELIVER (delivered ONLY to the default SMS app).
 */

/**
 * Receives SMS_DELIVER. Extracts fields from the PDUs and hands them to the pure-Kotlin
 * [com.wingman.integrations.sms.SmsInboundProcessor]. It never sends anything: this class
 * has no access to the sender (no autonomous reply).
 *
 * As the default SMS app, Wingman must also write the message to the system SMS provider,
 * otherwise it would be missing from every SMS app. That happens only for a NEW message, so a
 * duplicate broadcast does not add a second provider row.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val first = parts.first()
        val address = first.displayOriginatingAddress ?: first.originatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val sub = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra(LEGACY_EXTRA_SUBSCRIPTION, -1)).takeIf { it >= 0 }
        val sms = runCatching {
            IncomingSms(
                originatingAddress = address,
                body = body.take(20_000),
                serviceCenterTimestampMillis = first.timestampMillis,
                receivedAtMillis = System.currentTimeMillis(),
                subscriptionId = sub,
                partCount = parts.size,
            )
        }.getOrNull() ?: return

        val pending = goAsync()
        Thread {
            try {
                SmsConnectorRuntime.rememberDebugInbound(context, sms)
                val connector = SmsConnectorRuntime.get(context)
                val outcome = connector?.inbound?.onSmsReceived(sms)
                // Unconfigured connector: still behave as a correct default SMS app.
                if (outcome == null || outcome is SmsInboundOutcome.Accepted) {
                    SmsProviderWriter.insertInbox(context, sms)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        /** SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX (API 30+). */
        const val EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
        /** Pre-API-30 key used by the telephony stack (PhoneConstants.SUBSCRIPTION_KEY). UNVERIFIED per OEM. */
        const val LEGACY_EXTRA_SUBSCRIPTION = "subscription"
    }
}

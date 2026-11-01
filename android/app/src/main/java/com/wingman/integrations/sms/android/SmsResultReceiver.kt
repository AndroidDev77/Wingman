package com.wingman.integrations.sms.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import com.wingman.integrations.sms.SmsDeliveryStatus
import com.wingman.integrations.sms.SmsSentResult

/*
 * THIN ANDROID ADAPTER (WING-016). UNVERIFIED on device. Declared NOT exported: only our own
 * PendingIntents (explicit component) reach it.
 */

/** Routes the platform's per-part "sent" and "delivered" results into SmsSender. */
class SmsResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val part = intent.getIntExtra(EXTRA_PART, -1)
        if (part < 0) return
        val code = resultCode
        val pending = goAsync()
        Thread {
            try {
                val sender = SmsConnectorRuntime.get(context)?.sender ?: return@Thread
                when (intent.action) {
                    ACTION_SENT -> sender.onPartSent(key, part, mapSentResult(code))
                    ACTION_DELIVERED -> sender.onPartDelivered(key, part, deliveryStatus(intent))
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun deliveryStatus(intent: Intent): SmsDeliveryStatus {
        val pdu = intent.getByteArrayExtra("pdu") ?: return SmsDeliveryStatus.PENDING
        val format = intent.getStringExtra("format")
        val message = SmsMessage.createFromPdu(pdu, format) ?: return SmsDeliveryStatus.PENDING
        // GSM TP-Status. UNVERIFIED for CDMA status reports, which encode status differently.
        return SmsDeliveryStatus.fromTpStatus(message.status)
    }

    companion object {
        const val ACTION_SENT = "com.wingman.integrations.sms.SENT"
        const val ACTION_DELIVERED = "com.wingman.integrations.sms.DELIVERED"
        const val EXTRA_KEY = "wingman.sms.idempotency_key"
        const val EXTRA_PART = "wingman.sms.part"

        /** SDK result code to the pure-Kotlin result. Constants are inlined at compile time. */
        @SuppressLint("InlinedApi")
        fun mapSentResult(code: Int): SmsSentResult = when (code) {
            Activity.RESULT_OK -> SmsSentResult.Ok
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> SmsSentResult.GenericFailure
            SmsManager.RESULT_ERROR_RADIO_OFF -> SmsSentResult.RadioOff
            SmsManager.RESULT_ERROR_NULL_PDU -> SmsSentResult.NullPdu
            SmsManager.RESULT_ERROR_NO_SERVICE -> SmsSentResult.NoService
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> SmsSentResult.LimitExceeded
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> SmsSentResult.FdnCheckFailure
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> SmsSentResult.ShortCodeNotAllowed
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> SmsSentResult.ShortCodeNeverAllowed
            SmsManager.RESULT_RADIO_NOT_AVAILABLE -> SmsSentResult.RadioNotAvailable
            SmsManager.RESULT_NETWORK_REJECT -> SmsSentResult.NetworkReject
            else -> SmsSentResult.Other(code)
        }
    }
}

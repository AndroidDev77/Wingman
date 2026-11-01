package com.wingman.integrations.sms.android

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import com.wingman.integrations.sms.IncomingSms

/*
 * THIN ANDROID ADAPTER (WING-016). UNVERIFIED on device. Only the default SMS app may write
 * to the SMS provider; writes from a non-default app are silently ignored by the platform.
 */

/** Best-effort writes to the system SMS provider, which the default SMS app is responsible for. */
internal object SmsProviderWriter {

    fun insertInbox(context: Context, sms: IncomingSms) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, sms.originatingAddress)
            put(Telephony.Sms.BODY, sms.body)
            put(Telephony.Sms.DATE, sms.receivedAtMillis)
            put(Telephony.Sms.DATE_SENT, sms.serviceCenterTimestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            sms.subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        runCatching { context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }
    }

    fun insertSent(context: Context, destination: String, text: String, subscriptionId: Int?) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, destination)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        runCatching { context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) }
    }
}

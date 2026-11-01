package com.wingman.integrations.sms.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import com.wingman.integrations.sms.SmsTransport

/*
 * THIN ANDROID ADAPTER (WING-016). UNVERIFIED on device and not compiled in the authoring
 * environment. Only called from SmsSender.send, which only the authorized dispatch path calls.
 */

/**
 * [SmsTransport] over [SmsManager]. Splits with `divideMessage` and sends all parts with
 * `sendMultipartTextMessage`, attaching one "sent" and (optionally) one "delivered"
 * PendingIntent per part, each addressed explicitly to [SmsResultReceiver] and made distinct by
 * a data URI (`wingman-sms://sent/<key>/<part>`) so the system never merges them.
 *
 * The default SMS app is responsible for writing its sent messages to the SMS provider; that
 * is done here best-effort after a successful submit (see [SmsProviderWriter]).
 */
class AndroidSmsTransport(private val context: Context) : SmsTransport {

    private fun smsManager(subscriptionId: Int?): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (subscriptionId == null || subscriptionId < 0) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.createForSubscriptionId(subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }

    override fun submit(
        destination: String,
        text: String,
        subscriptionId: Int?,
        idempotencyKey: String,
        requestDeliveryReports: Boolean,
    ): Int {
        val manager = smsManager(subscriptionId)
        val parts = manager.divideMessage(text)
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered = ArrayList<PendingIntent?>(parts.size)
        for (i in parts.indices) {
            sent += resultIntent(SmsResultReceiver.ACTION_SENT, idempotencyKey, i, mutable = false)
            // The delivered intent must be mutable: the platform fills in the status-report "pdu" extra.
            delivered += if (requestDeliveryReports) resultIntent(SmsResultReceiver.ACTION_DELIVERED, idempotencyKey, i, mutable = true) else null
        }
        // Throws SecurityException without SEND_SMS, IllegalArgumentException for an empty destination/text.
        manager.sendMultipartTextMessage(destination, null, parts, sent, if (requestDeliveryReports) delivered else null)
        SmsProviderWriter.insertSent(context, destination, text, subscriptionId)
        return parts.size
    }

    private fun resultIntent(action: String, key: String, part: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(action)
            .setClass(context, SmsResultReceiver::class.java)
            .setData(Uri.Builder().scheme("wingman-sms").authority(action.substringAfterLast('.')).appendPath(key).appendPath(part.toString()).build())
            .putExtra(SmsResultReceiver.EXTRA_KEY, key)
            .putExtra(SmsResultReceiver.EXTRA_PART, part)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else if (!mutable) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, (key + action + part).hashCode(), intent, flags)
    }
}

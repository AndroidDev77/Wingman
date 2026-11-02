package com.wingman.integrations.notifications.android

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.wingman.integrations.notifications.FireResult
import com.wingman.integrations.notifications.ReplyActionHandle

/*
 * THIN ANDROID ADAPTER (WING-017). Compiled in CI; never run on a device (I-003).
 *
 * Fills every free-form RemoteInput of ONE notification action with the reply text and sends its
 * PendingIntent: the same thing the system UI does when the user types into the notification's
 * reply box. It is called only by NotificationReplyAdapter, after the key is persisted and every
 * consent/lease check passed. It never taps, never uses accessibility, never opens the app.
 */
internal class AndroidReplyActionHandle(
    private val context: Context,
    private val action: Notification.Action,
) : ReplyActionHandle {

    override fun fire(text: String): FireResult {
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput } ?: emptyList()
        if (inputs.isEmpty()) return FireResult.Error("NoFreeFormRemoteInput")
        val pending: PendingIntent = action.actionIntent ?: return FireResult.Error("NoPendingIntent")
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, results)
        return try {
            pending.send(context, 0, intent)
            FireResult.Fired
        } catch (e: PendingIntent.CanceledException) {
            FireResult.Canceled
        } catch (e: RuntimeException) {
            FireResult.Error(e.javaClass.simpleName)
        }
    }
}

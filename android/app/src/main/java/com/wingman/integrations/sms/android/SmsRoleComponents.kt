package com.wingman.integrations.sms.android

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/*
 * THIN ANDROID ADAPTERS (WING-016). UNVERIFIED on device and not compiled in the authoring
 * environment. These components exist because Android only lists an app as a candidate for the
 * default-SMS-app role (RoleManager.ROLE_SMS) if it declares all four: an SMS_DELIVER receiver,
 * a WAP_PUSH_DELIVER receiver, a RESPOND_VIA_MESSAGE service and a SENDTO activity.
 * See docs/integrations/android_sms.md for the manifest patch and the MVP limitations.
 */

/**
 * WAP_PUSH_DELIVER (MMS). MMS is OUT OF SCOPE for WING-016: incoming MMS is not downloaded or
 * stored. This is a real user-facing loss while Wingman is the default SMS app, and is listed
 * as a blocker for any non-test rollout in the doc.
 */
class MmsWapPushDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty (MMS unsupported). No envelope, no send.
    }
}

/**
 * RESPOND_VIA_MESSAGE ("reply with a message" when declining a call). Not supported in the
 * MVP: it would be a send that bypasses the authorized dispatch path, so it is refused.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/**
 * SENDTO / sms: compose entry point. The MVP has no SMS compose screen, so this forwards to
 * the app's launcher activity (the inbox, WING-014). It never sends.
 */
class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}

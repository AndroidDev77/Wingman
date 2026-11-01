package com.wingman.integrations.sms.android

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import com.wingman.integrations.sms.SmsDeviceState
import com.wingman.integrations.sms.SmsSendPreconditions

/*
 * THIN ANDROID ADAPTER (WING-016). UNVERIFIED on device and not compiled in the authoring
 * environment.
 */

/** Default-SMS-app role request and device probing. */
object SmsRoleHelper {

    fun isDefaultSmsApp(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    /**
     * Intent that asks the user to make Wingman the default SMS app. Start it with
     * `registerForActivityResult(StartActivityForResult())` from a screen the user opened on
     * purpose (WING-013 onboarding). Null when the role is unavailable or already held.
     */
    fun requestRoleIntent(context: Context): Intent? {
        if (isDefaultSmsApp(context)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(RoleManager.ROLE_SMS)) null else rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun deviceState(context: Context): SmsDeviceState = SmsDeviceState(
        hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        isDefaultSmsApp = isDefaultSmsApp(context),
        receiveSmsGranted = granted(context, Manifest.permission.RECEIVE_SMS),
        readSmsGranted = granted(context, Manifest.permission.READ_SMS),
        sendSmsGranted = granted(context, Manifest.permission.SEND_SMS),
        // Deliberately not probed: notification access is never SMS capability.
        notificationListenerEnabled = false,
    )

    fun sendPreconditions(context: Context): SmsSendPreconditions {
        val d = deviceState(context)
        return SmsSendPreconditions(hasTelephony = d.hasTelephony, sendPermissionGranted = d.sendSmsGranted)
    }
}

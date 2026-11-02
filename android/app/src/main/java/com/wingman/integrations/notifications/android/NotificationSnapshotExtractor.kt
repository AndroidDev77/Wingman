package com.wingman.integrations.notifications.android

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import com.wingman.integrations.notifications.NotificationSnapshot
import com.wingman.integrations.notifications.NotificationSource
import com.wingman.integrations.notifications.SnapshotAction
import com.wingman.integrations.notifications.SnapshotMessage
import com.wingman.integrations.notifications.SnapshotRemoteInput
import java.security.MessageDigest

/*
 * THIN ANDROID ADAPTER (WING-017). Compiled in CI; never run on a device (I-003). It only copies
 * fields into the pure-Kotlin snapshot; every decision lives in `NotificationListenerCore`.
 * Never logs anything it reads.
 */
internal object NotificationSnapshotExtractor {

    fun snapshot(sbn: StatusBarNotification): NotificationSnapshot {
        val n = sbn.notification
        val extras: Bundle = n.extras ?: Bundle.EMPTY
        return NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            postTimeMillis = sbn.postTime,
            whenMillis = n.`when`,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            messages = messages(extras),
            shortcutId = n.shortcutId,
            groupKey = n.group,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            category = n.category,
            channelId = n.channelId,
            template = extras.getString(Notification.EXTRA_TEMPLATE),
            hasPublicVersion = n.publicVersion != null,
            actions = actions(n),
        )
    }

    @Suppress("DEPRECATION")
    private fun messages(extras: Bundle): List<SnapshotMessage> {
        val raw: Array<Parcelable> = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return raw.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            // Keys of Notification.MessagingStyle.Message#toBundle (stable since API 24).
            val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.getParcelable<android.app.Person>("sender_person") else null
            SnapshotMessage(
                text = b.getCharSequence("text")?.toString(),
                timestampMillis = b.getLong("time", 0L),
                senderName = person?.name?.toString() ?: b.getCharSequence("sender")?.toString(),
                senderKey = person?.key,
                hasData = b.getString("type") != null || b.getParcelable<android.net.Uri>("uri") != null,
            )
        }
    }

    private fun actions(n: Notification): List<SnapshotAction> =
        (n.actions ?: emptyArray()).mapIndexed { index, a ->
            SnapshotAction(
                index = index,
                title = a.title?.toString(),
                remoteInputs = (a.remoteInputs ?: emptyArray()).map {
                    SnapshotRemoteInput(it.resultKey ?: "", it.allowFreeFormInput, it.choices?.size ?: 0)
                },
                semanticAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) a.semanticAction else SnapshotAction.SEMANTIC_ACTION_NONE,
                isContextual = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && a.isContextual,
            )
        }

    /**
     * Package plus signing-certificate SHA-256 digests. On API 30+ the package must be visible to
     * Wingman (`<queries>` in the manifest); otherwise the digests are unavailable (null) and a
     * plugin that pins digests rejects the notification.
     */
    fun source(context: Context, packageName: String): NotificationSource =
        NotificationSource(packageName, runCatching { certDigests(context.packageManager, packageName) }.getOrNull())

    @Suppress("DEPRECATION")
    private fun certDigests(pm: PackageManager, packageName: String): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        } ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet().ifEmpty { null }
    }
}

package com.wingman.integrations.sms

import com.wingman.core.model.UtcTimestamps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * What the device looks like right now, read by the Android adapter (`SmsDeviceProbe`).
 *
 * [notificationListenerEnabled] is accepted ONLY so tests can prove it is ignored: notification
 * access is never SMS capability (ticket constraint, ADR 0005). It is not an input to any decision.
 */
data class SmsDeviceState(
    val hasTelephony: Boolean,
    val isDefaultSmsApp: Boolean,
    val receiveSmsGranted: Boolean,
    val readSmsGranted: Boolean,
    val sendSmsGranted: Boolean,
    val notificationListenerEnabled: Boolean = false,
)

/**
 * A recorded physical-device verification (docs/integrations/android_sms.md, "Device test
 * checklist"). Only a human-recorded run may produce one; code never fabricates it.
 */
data class SmsDeviceVerification(val evidence: String, val verifiedAtMillis: Long) {
    init {
        require(evidence.isNotBlank() && evidence.length <= 500) { "evidence must be 1..500 chars" }
    }
}

/**
 * Builds the `connector_capabilities.v1` document for platform `sms`.
 *
 * - `read`, `send`: UNVERIFIED until a [SmsDeviceVerification] is supplied for that capability;
 *   UNSUPPORTED on a device with no telephony. Missing role/permissions keep them UNVERIFIED
 *   (the user can still grant them) with a note saying what is missing; the sender refuses with
 *   `permission_denied` until then.
 * - `draft`: UNSUPPORTED (the SMS connector never writes into another app's compose field).
 * - `swipe`, `calendar_write`: UNSUPPORTED, always.
 * - `send.reports_delivery_receipts = true`: SMS status reports are requested; whether a carrier
 *   honours them is UNVERIFIED.
 */
object SmsCapabilities {

    fun build(
        device: SmsDeviceState,
        assessedAtMillis: Long,
        readVerification: SmsDeviceVerification? = null,
        sendVerification: SmsDeviceVerification? = null,
    ): JsonObject = buildJsonObject {
        put("schema_version", "connector_capabilities.v1")
        put("platform", SMS_PLATFORM)
        put("connector_version", SMS_CONNECTOR_VERSION)
        putJsonObject("capabilities") {
            put("read", readCapability(device, readVerification))
            put("draft", unsupported("The SMS connector never places drafts into another app."))
            put("send", sendCapability(device, sendVerification))
            put("swipe", unsupported(null))
            put("calendar_write", unsupported(null))
        }
        put("assessed_at", UtcTimestamps.format(Instant.ofEpochMilli(assessedAtMillis)))
    }

    /** True when the dispatcher may hand this device a send at all (policy for UNVERIFIED stays server-side). */
    fun canAttemptSend(device: SmsDeviceState): Boolean = device.hasTelephony && device.sendSmsGranted

    private fun unsupported(notes: String?): JsonObject = buildJsonObject {
        put("status", "UNSUPPORTED")
        if (notes != null) put("notes", notes)
    }

    private fun readCapability(device: SmsDeviceState, verification: SmsDeviceVerification?): JsonObject {
        if (!device.hasTelephony) return unsupported("Device has no telephony feature.")
        val missing = buildList {
            if (!device.isDefaultSmsApp) add("default-SMS-app role")
            if (!device.receiveSmsGranted) add("RECEIVE_SMS")
        }
        return capability(verification, missing, deliveryReceipts = null)
    }

    private fun sendCapability(device: SmsDeviceState, verification: SmsDeviceVerification?): JsonObject {
        if (!device.hasTelephony) return unsupported("Device has no telephony feature.")
        val missing = buildList {
            if (!device.isDefaultSmsApp) add("default-SMS-app role")
            if (!device.sendSmsGranted) add("SEND_SMS")
        }
        return capability(verification, missing, deliveryReceipts = true)
    }

    private fun capability(verification: SmsDeviceVerification?, missing: List<String>, deliveryReceipts: Boolean?): JsonObject = buildJsonObject {
        // A recorded verification only counts while the device still has what was verified.
        val verified = verification != null && missing.isEmpty()
        if (verified) {
            put("status", "VERIFIED")
            put("evidence", verification!!.evidence)
            put("verified_at", UtcTimestamps.format(Instant.ofEpochMilli(verification.verifiedAtMillis)))
        } else {
            put("status", "UNVERIFIED")
            val note = if (missing.isEmpty()) {
                "Implemented; pending the physical-device checklist (WING-016)."
            } else {
                "Pending physical-device test (WING-016). Missing: ${missing.joinToString(", ")}."
            }
            put("notes", note)
        }
        if (deliveryReceipts != null) put("reports_delivery_receipts", deliveryReceipts)
    }
}

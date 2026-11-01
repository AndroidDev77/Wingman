package com.wingman.integrations.sms

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsCapabilitiesTest {
    private val full = SmsDeviceState(hasTelephony = true, isDefaultSmsApp = true, receiveSmsGranted = true, readSmsGranted = true, sendSmsGranted = true)
    private val at = 1_793_473_200_000L

    private fun JsonObject.cap(name: String) = this["capabilities"]!!.jsonObject[name]!!.jsonObject
    private fun JsonObject.status(name: String) = cap(name).str("status")

    @Test fun `default publication - read and send UNVERIFIED, the rest UNSUPPORTED`() {
        val c = SmsCapabilities.build(full, at)
        assertEquals("connector_capabilities.v1", c.str("schema_version"))
        assertEquals("sms", c.str("platform"))
        assertEquals("UNVERIFIED", c.status("read"))
        assertEquals("UNVERIFIED", c.status("send"))
        assertEquals("UNSUPPORTED", c.status("draft"))
        assertEquals("UNSUPPORTED", c.status("swipe"))
        assertEquals("UNSUPPORTED", c.status("calendar_write"))
        assertEquals("true", c.cap("send")["reports_delivery_receipts"].toString())
        assertEquals("2026-10-31T19:00:00Z", c.str("assessed_at"))
    }

    @Test fun `notification access is never SMS capability`() {
        val none = SmsDeviceState(hasTelephony = true, isDefaultSmsApp = false, receiveSmsGranted = false, readSmsGranted = false, sendSmsGranted = false)
        val withListener = none.copy(notificationListenerEnabled = true)
        assertEquals(SmsCapabilities.build(none, at), SmsCapabilities.build(withListener, at))
        assertFalse(SmsCapabilities.canAttemptSend(withListener))
        assertTrue(SmsCapabilities.build(withListener, at).cap("send").str("notes")!!.contains("SEND_SMS"))
    }

    @Test fun `no telephony makes read and send UNSUPPORTED`() {
        val c = SmsCapabilities.build(full.copy(hasTelephony = false), at)
        assertEquals("UNSUPPORTED", c.status("read"))
        assertEquals("UNSUPPORTED", c.status("send"))
    }

    @Test fun `VERIFIED only with a recorded device run and the role still held`() {
        val v = SmsDeviceVerification("docs/integrations/android_sms.md#run-2026-11-02 (Pixel 7, T-Mobile)", at)
        val c = SmsCapabilities.build(full, at, readVerification = v, sendVerification = v)
        assertEquals("VERIFIED", c.status("read"))
        assertEquals("VERIFIED", c.status("send"))
        assertEquals("2026-10-31T19:00:00Z", c.cap("send").str("verified_at"))
        // Role revoked: falls back to UNVERIFIED even with a recorded run.
        val lost = SmsCapabilities.build(full.copy(isDefaultSmsApp = false), at, readVerification = v, sendVerification = v)
        assertEquals("UNVERIFIED", lost.status("send"))
        assertEquals("UNVERIFIED", lost.status("read"))
    }

    @Test fun `capabilities_changed envelope wraps the document`() {
        val e = SmsEnvelopeFactory(SCOPE).capabilitiesChanged(SmsCapabilities.build(full, at), at).json
        assertEquals("connection.capabilities_changed", e.str("type"))
        assertEquals("null", e["external_message_id"].toString())
    }
}

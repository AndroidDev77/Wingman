package com.wingman.integrations.hinge

import com.wingman.core.model.UtcTimestamps
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * The plain-language ban-risk warning (D-021 item 2, matrix §6b). Its [VERSION] is what the
 * backend records as `consent_text_version`; change the version whenever the text changes.
 * The final wording and version are an owner decision (I-031).
 */
object HingeConsentText {
    const val VERSION = "d021-v1"
    const val TITLE = "Hinge (experimental): read this first"
    val BODY = listOf(
        "This connects Wingman to Hinge through an UNOFFICIAL interface. Hinge does not offer or allow it.",
        "Using it may break Hinge's terms of service. Hinge can restrict or permanently ban your account for it, without warning. You alone take that risk.",
        "Wingman logs in to Hinge from this phone with a code Hinge texts you. Your Hinge login stays encrypted on this phone. It is never sent to Wingman's servers.",
        "Wingman checks for new Hinge messages about every 15 minutes, sends only replies you approve (at most 20 a day), never swipes, never likes, and never writes first.",
        "If Hinge shows any warning, security check or error, Wingman stops at once and tells you.",
        "You can turn this off at any time. Turning it off deletes the Hinge login from this phone immediately.",
    )
    const val ACKNOWLEDGE_LABEL = "I understand that my Hinge account can be banned, and I accept that risk."
    const val ACCEPT_BUTTON = "I accept the risk"
}

/** What the user accepted, kept locally and sent (without any secret) when pairing. */
@Serializable
data class HingeConsentRecord(
    @SerialName("text_version") val textVersion: String,
    @SerialName("accepted_at") val acceptedAtMillis: Long,
    @SerialName("ban_risk_acknowledged") val banRiskAcknowledged: Boolean,
) {
    /** Contract 1.3.0 `ExperimentalConsentRequest`. */
    fun toContract(): JsonObject = buildJsonObject {
        put("platform", HINGE_PLATFORM)
        put("consent_text_version", textVersion)
        put("accepted_at", UtcTimestamps.format(Instant.ofEpochMilli(acceptedAtMillis - acceptedAtMillis % 1000)))
        put("ban_risk_acknowledged", banRiskAcknowledged)
    }
}

/**
 * The `connector_capabilities.v1` claim (matches `contracts/examples/capabilities.hinge_unofficial_api.json`).
 * A claim is never evidence: the server never stores a client `VERIFIED` (ADR 0007). Read and send
 * are UNVERIFIED until the owner's live test; swipe is UNSUPPORTED (D-021: never automated in v1);
 * draft is UNSUPPORTED (Wingman never writes into Hinge's compose field); profile_read is
 * UNSUPPORTED in v1 (the profile routes are not allowlisted).
 */
object HingeCapabilities {
    fun document(assessedAtMillis: Long): JsonObject = buildJsonObject {
        put("schema_version", "connector_capabilities.v1")
        put("platform", HINGE_PLATFORM)
        put("connector_version", HINGE_CONNECTOR_VERSION)
        putJsonObject("capabilities") {
            putJsonObject("read") {
                put("status", "UNVERIFIED")
                put("notes", "Experimental unofficial API ($HINGE_REFERENCE_COMMIT). Not yet tested on a real account.")
            }
            putJsonObject("draft") {
                put("status", "UNSUPPORTED")
                put("notes", "Wingman never places drafts into Hinge's compose field.")
            }
            putJsonObject("send") {
                put("status", "UNVERIFIED")
                put("reports_delivery_receipts", false)
                put("notes", "Approval-only until the owner's live test (D-021). Sendbird WebSocket echo confirms sent, not delivered.")
            }
            putJsonObject("swipe") {
                put("status", "UNSUPPORTED")
                put("notes", "Blocked by policy: no automated swiping, likes or first messages (D-021).")
            }
            putJsonObject("calendar_write") { put("status", "UNSUPPORTED") }
            putJsonObject("profile_read") {
                put("status", "UNSUPPORTED")
                put("notes", "Profile routes are not allowlisted in v1.")
            }
        }
        put("assessed_at", UtcTimestamps.format(Instant.ofEpochMilli(assessedAtMillis - assessedAtMillis % 1000)))
    }

    /** `createConnection` body (contract 1.3.0; example `connection.create.hinge_experimental.request.json`). */
    fun pairingBody(deviceInstallId: String, consent: HingeConsentRecord, assessedAtMillis: Long): JsonObject = buildJsonObject {
        put("platform", HINGE_PLATFORM)
        put("display_name", "Hinge (experimental)")
        put("transport", "unofficial_api")
        put("experimental", true)
        put("device_install_id", deviceInstallId)
        put("capabilities", document(assessedAtMillis))
        put("experimental_consent", consent.toContract())
    }
}

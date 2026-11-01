package com.wingman.integrations.sms

/**
 * Per-part result of the platform's "sent" PendingIntent, decoded by the Android adapter from
 * `resultCode` (Activity.RESULT_OK or an `SmsManager.RESULT_*` constant). The adapter does the
 * int-to-enum step with the SDK's own constants, so no magic numbers live in pure code.
 */
sealed interface SmsSentResult {
    data object Ok : SmsSentResult
    data object GenericFailure : SmsSentResult
    data object RadioOff : SmsSentResult
    data object NullPdu : SmsSentResult
    data object NoService : SmsSentResult
    data object LimitExceeded : SmsSentResult
    data object ShortCodeNotAllowed : SmsSentResult
    data object ShortCodeNeverAllowed : SmsSentResult
    data object FdnCheckFailure : SmsSentResult
    data object RadioNotAvailable : SmsSentResult
    data object NetworkReject : SmsSentResult
    /** Any other `SmsManager.RESULT_*` value (the list grows per API level). */
    data class Other(val resultCode: Int) : SmsSentResult
}

/** Per-part delivery report (`SmsMessage.getStatus()` from the status-report PDU). */
enum class SmsDeliveryStatus {
    /** TP-Status 0x00..0x1F: short message received by the recipient handset. */
    DELIVERED,
    /** TP-Status 0x20..0x3F: SMSC still trying. Not final. */
    PENDING,
    /** TP-Status >= 0x40: SMSC gave up. */
    FAILED,
    ;

    companion object {
        /**
         * GSM TP-Status (3GPP TS 23.040 9.2.3.15). CDMA status reports use a different
         * encoding that the adapter must normalise first; UNVERIFIED on CDMA carriers.
         */
        fun fromTpStatus(status: Int): SmsDeliveryStatus = when {
            status < 0 -> PENDING // STATUS_NONE / unknown: not final
            status <= 0x1F -> DELIVERED
            status <= 0x3F -> PENDING
            else -> FAILED
        }
    }
}

/** Maps every failure source into the contract's typed failure codes. Pure and exhaustive. */
object SmsFailureMapper {

    /**
     * Failure of one part as reported by the "sent" PendingIntent.
     * `retryable` means "a NEW send attempt with a NEW key may succeed later"; it never
     * means this connector will retry (D-006).
     */
    fun fromSentResult(result: SmsSentResult): SmsSendFailure? = when (result) {
        SmsSentResult.Ok -> null
        SmsSentResult.RadioOff -> SmsSendFailure(SmsFailureCode.NOT_CONNECTED, true, "radio off (airplane mode?)")
        SmsSentResult.NoService -> SmsSendFailure(SmsFailureCode.NOT_CONNECTED, true, "no cellular service")
        SmsSentResult.RadioNotAvailable -> SmsSendFailure(SmsFailureCode.NOT_CONNECTED, true, "radio not available")
        SmsSentResult.GenericFailure -> SmsSendFailure(SmsFailureCode.TRANSPORT_ERROR, true, "generic failure")
        SmsSentResult.NullPdu -> SmsSendFailure(SmsFailureCode.TRANSPORT_ERROR, false, "null PDU (message could not be encoded)")
        SmsSentResult.LimitExceeded -> SmsSendFailure(SmsFailureCode.REJECTED_BY_PLATFORM, true, "OS SMS rate limit exceeded")
        SmsSentResult.NetworkReject -> SmsSendFailure(SmsFailureCode.REJECTED_BY_PLATFORM, true, "rejected by network")
        SmsSentResult.FdnCheckFailure -> SmsSendFailure(SmsFailureCode.REJECTED_BY_PLATFORM, false, "blocked by fixed dialing numbers")
        SmsSentResult.ShortCodeNotAllowed -> SmsSendFailure(SmsFailureCode.PERMISSION_DENIED, false, "premium short code not allowed")
        SmsSentResult.ShortCodeNeverAllowed -> SmsSendFailure(SmsFailureCode.PERMISSION_DENIED, false, "premium short code never allowed")
        is SmsSentResult.Other -> SmsSendFailure(SmsFailureCode.TRANSPORT_ERROR, false, "platform result code ${result.resultCode}")
    }

    /** Synchronous failure thrown by the SmsManager call itself (nothing was transmitted). */
    fun fromSubmitException(e: Throwable): SmsSendFailure = when (e) {
        is SecurityException -> SmsSendFailure(SmsFailureCode.PERMISSION_DENIED, false, "SEND_SMS not granted")
        is IllegalArgumentException -> SmsSendFailure(SmsFailureCode.TRANSPORT_ERROR, false, "invalid destination or message")
        else -> SmsSendFailure(SmsFailureCode.TRANSPORT_ERROR, true, "submit failed: ${e.javaClass.simpleName}")
    }

    /**
     * Several parts failed differently: report the first part's failure, but a message where
     * some parts went out and others did not is never retryable (a retry would resend parts
     * the recipient already has).
     */
    fun combine(failures: List<SmsSendFailure>, anyPartOk: Boolean): SmsSendFailure {
        val first = failures.first()
        return if (anyPartOk) {
            SmsSendFailure(first.code, false, "partial send: ${failures.size} part(s) failed; first: ${first.detail}".take(500))
        } else {
            first
        }
    }

    /** No "sent" result arrived in time. The outcome is unknown, so a retry could double-send. */
    val SENT_RESULT_TIMEOUT = SmsSendFailure(
        SmsFailureCode.TIMEOUT,
        false,
        "no sent result from the platform in time; outcome unknown, the message may have been sent",
    )

    val PERMISSION_MISSING = SmsSendFailure(SmsFailureCode.PERMISSION_DENIED, false, "SEND_SMS not granted")
    val NO_TELEPHONY = SmsSendFailure(SmsFailureCode.NOT_CONNECTED, false, "device has no telephony feature")
    val INVALID_THREAD = SmsSendFailure(SmsFailureCode.TRANSPORT_ERROR, false, "external_thread_id is not an sms: thread")
    val CAPABILITY_UNSUPPORTED = SmsSendFailure(SmsFailureCode.CAPABILITY_UNSUPPORTED, false, "send capability is UNSUPPORTED on this device")
}

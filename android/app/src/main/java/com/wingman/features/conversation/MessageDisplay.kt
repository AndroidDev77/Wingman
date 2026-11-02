package com.wingman.features.conversation

import com.wingman.core.model.DeliveryState
import com.wingman.core.model.Message
import com.wingman.core.model.MessageDirection
import com.wingman.core.model.MessageOrigin
import java.time.ZoneId
import java.util.Locale

/*
 * Message display models for the conversation screen. Pure Kotlin, computed off the main thread
 * (see ConversationState) so a 100+ message thread renders without per-row work in composition.
 */

/** Honest delivery label (contract "Delivery truth"): queued is not sent; sent is not delivered. */
data class DeliveryLabel(val text: String, val tone: LabelTone, val isFailure: Boolean)

object DeliveryLabels {
    fun of(m: Message): DeliveryLabel = when (m.deliveryState) {
        DeliveryState.RECEIVED -> DeliveryLabel("Received", LabelTone.NEUTRAL, false)
        DeliveryState.QUEUED -> DeliveryLabel("Queued, not sent yet", LabelTone.PENDING, false)
        DeliveryState.SENDING -> DeliveryLabel("Sending…", LabelTone.PENDING, false)
        // `sent` means the connector handed it to the transport; it does NOT mean delivered.
        DeliveryState.SENT -> DeliveryLabel("Sent", LabelTone.NEUTRAL, false)
        DeliveryState.DELIVERED -> DeliveryLabel("Delivered", LabelTone.SUCCESS, false)
        DeliveryState.FAILED -> DeliveryLabel(
            if (m.failure?.retryable == false) "Failed" else "Failed, will retry",
            LabelTone.ERROR,
            true,
        )
        DeliveryState.DEAD_LETTERED -> DeliveryLabel("Failed, not sent", LabelTone.ERROR, true)
        DeliveryState.CANCELED -> DeliveryLabel("Canceled, not sent", LabelTone.WARNING, false)
        // Contract 1.3.0: the user's own report; Wingman never saw it go out or arrive.
        DeliveryState.USER_REPORTED_SENT -> DeliveryLabel("You reported sending this (not confirmed)", LabelTone.NEUTRAL, false)
    }
}

data class MessageRow(
    /** Stable LazyColumn key: the message ID. */
    val key: String,
    val outbound: Boolean,
    val text: String,
    /** Formatted `created_at` (inbound: platform send time; outbound: queue time). */
    val time: String,
    /** Null for inbound messages: "Received" is implied and adds noise to every bubble. */
    val delivery: DeliveryLabel?,
    /** e.g. "Sent by autopilot"; null for contact and user-typed messages. */
    val originNote: String?,
    /** Non-null when failed: the connector's failure code, never message content. */
    val failureNote: String?,
    /** True when this row starts a new calendar day in [ZoneId] (for a day divider). */
    val startsNewDay: Boolean,
    val dayLabel: String,
)

object MessageRows {
    /**
     * Contract order is `created_at` ascending, then `id`. Pages are re-sorted defensively (and
     * de-duplicated by ID, last one wins) so a refresh racing a page load can't misorder or
     * duplicate rows, which would also break LazyColumn's unique-key requirement.
     */
    fun chronological(messages: List<Message>): List<Message> =
        messages.associateBy { it.id }.values.sortedWith(compareBy<Message>({ it.createdAt }, { it.id }))

    fun build(messages: List<Message>, zone: ZoneId, locale: Locale = Locale.getDefault()): List<MessageRow> {
        val dayFormat = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale)
        var previousDay: java.time.LocalDate? = null
        return chronological(messages).map { m ->
            val day = m.createdAt.atZone(zone).toLocalDate()
            val newDay = day != previousDay
            previousDay = day
            val outbound = m.direction == MessageDirection.OUTBOUND
            MessageRow(
                key = m.id,
                outbound = outbound,
                text = m.text,
                time = TimeFormat.messageTime(m.createdAt, zone, locale),
                delivery = if (outbound) DeliveryLabels.of(m) else null,
                originNote = when (m.origin) {
                    MessageOrigin.APPROVED_DRAFT -> "Approved draft"
                    MessageOrigin.AUTOPILOT -> "Sent by autopilot"
                    MessageOrigin.ASSISTED_DRAFT -> "Draft you sent by hand"
                    MessageOrigin.CONTACT, MessageOrigin.USER_MANUAL -> null
                },
                failureNote = m.failure?.takeIf {
                    m.deliveryState == DeliveryState.FAILED || m.deliveryState == DeliveryState.DEAD_LETTERED
                }?.let { "Reason: ${it.code}" },
                startsNewDay = newDay,
                dayLabel = dayFormat.format(day),
            )
        }
    }
}

package com.wingman.features.settings

import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.model.ApprovalReason
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Preferences
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.Tone
import com.wingman.core.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PreferenceFormTest {

    private fun valid(form: PreferenceForm) = (PreferenceValidation.toUpdate(form) as Validated.Valid).value
    private fun invalid(form: PreferenceForm) = (PreferenceValidation.toUpdate(form) as Validated.Invalid).errors

    @Test fun allFiveIntentModesAreValid() {
        assertEquals(5, IntentMode.entries.size)
        IntentMode.entries.forEach { mode ->
            val desc = if (mode == IntentMode.CUSTOM) "  Slow burn, see where it goes  " else "ignored"
            val update = valid(PreferenceForm(intentMode = mode, customDescription = desc))
            assertEquals(mode, update.intent.mode)
            // Contract: custom_description is required for custom and must be null otherwise.
            if (mode == IntentMode.CUSTOM) assertEquals("Slow burn, see where it goes", update.intent.customDescription)
            else assertNull(update.intent.customDescription)
        }
    }

    @Test fun customWithoutDescriptionIsRejected() {
        listOf("", "   ", "\n").forEach {
            val errors = invalid(PreferenceForm(intentMode = IntentMode.CUSTOM, customDescription = it))
            assertTrue(FormField.CUSTOM_DESCRIPTION in errors)
        }
        val tooLong = invalid(PreferenceForm(intentMode = IntentMode.CUSTOM, customDescription = "x".repeat(501)))
        assertTrue(FormField.CUSTOM_DESCRIPTION in tooLong)
        valid(PreferenceForm(intentMode = IntentMode.CUSTOM, customDescription = "x".repeat(500)))
    }

    @Test fun noIntentChosenIsRejected() {
        assertTrue(FormField.INTENT_MODE in invalid(PreferenceForm()))
    }

    @Test fun neverSavedUserGetsNothingPrefilled() {
        // The mock's defaults say SERIOUS; the form must not pre-choose it for a version-0 user.
        val form = PreferenceForm.from(MockFixtures.defaultPreferences)
        assertNull(form.intentMode)
        assertTrue(form.style.isEmpty)
        assertEquals(AutomationMode.MANUAL, form.automation.mode)
        assertEquals(AutomationForm(), form.automation)
    }

    @Test fun emptyStyleSavesAllAttributesNull() {
        val style = valid(PreferenceForm(intentMode = IntentMode.OPEN)).style
        assertEquals(StyleProfile(styleSamples = emptyList()), style)
    }

    @Test fun blankSamplesAreDroppedAndLimitsEnforced() {
        val form = PreferenceForm(intentMode = IntentMode.OPEN, style = StyleForm(samples = listOf(" hey you ", "", "  ")))
        assertEquals(listOf("hey you"), valid(form).style.styleSamples)
        assertTrue(FormField.STYLE_SAMPLES in invalid(form.copy(style = StyleForm(samples = List(21) { "s$it" }))))
        assertTrue(FormField.STYLE_SAMPLES in invalid(form.copy(style = StyleForm(samples = listOf("x".repeat(1001))))))
    }

    @Test fun automationValidation() {
        val base = PreferenceForm(intentMode = IntentMode.OPEN)
        fun auto(a: AutomationForm) = invalid(base.copy(automation = a))

        assertTrue(FormField.TIMEZONE in auto(AutomationForm(activeHoursEnabled = true, timezone = "Mars/Base", windows = listOf(WindowForm(setOf(Weekday.MON), "09:00", "17:00")))))
        assertTrue(FormField.ACTIVE_WINDOWS in auto(AutomationForm(activeHoursEnabled = true, timezone = "UTC")))
        assertTrue(FormField.ACTIVE_WINDOWS in auto(AutomationForm(activeHoursEnabled = true, timezone = "UTC", windows = listOf(WindowForm(emptySet(), "09:00", "17:00")))))
        assertTrue(FormField.ACTIVE_WINDOWS in auto(AutomationForm(activeHoursEnabled = true, timezone = "UTC", windows = listOf(WindowForm(setOf(Weekday.MON), "9:00", "24:00")))))
        assertTrue(FormField.REPLY_DELAY in auto(AutomationForm(replyDelayEnabled = true, replyDelayMin = "600", replyDelayMax = "60")))
        assertTrue(FormField.REPLY_DELAY in auto(AutomationForm(replyDelayEnabled = true, replyDelayMin = "-1", replyDelayMax = "60")))
        assertTrue(FormField.REPLY_DELAY in auto(AutomationForm(replyDelayEnabled = true, replyDelayMin = "0", replyDelayMax = "86401")))
        assertTrue(FormField.DAILY_LIMIT in auto(AutomationForm(dailySendLimit = "1001")))
        assertTrue(FormField.DAILY_LIMIT in auto(AutomationForm(dailySendLimit = "lots")))

        val ok = valid(
            base.copy(
                automation = AutomationForm(
                    activeHoursEnabled = true, timezone = " America/Chicago ",
                    windows = listOf(WindowForm(setOf(Weekday.FRI, Weekday.MON), "17:00", "02:00")),
                    replyDelayEnabled = true, replyDelayMin = "60", replyDelayMax = "600",
                    dailySendLimit = " 30 ",
                    approvalRequiredFor = setOf(ApprovalReason.SENSITIVE_CONTENT, ApprovalReason.FIRST_MESSAGE),
                ),
            ),
        ).automation
        assertEquals(AutomationMode.MANUAL, ok.mode)
        assertEquals("America/Chicago", ok.activeHours!!.timezone)
        assertEquals(listOf(Weekday.MON, Weekday.FRI), ok.activeHours!!.windows.single().days)
        assertEquals(60, ok.replyDelaySeconds!!.min)
        assertEquals(30, ok.dailySendLimit)
        assertEquals(listOf(ApprovalReason.SENSITIVE_CONTENT, ApprovalReason.FIRST_MESSAGE), ok.approvalRequiredFor)

        // Disabled sections and a blank limit are sent as null (= no restriction / server default).
        val off = valid(base.copy(automation = AutomationForm(timezone = "garbage", replyDelayMin = "x"))).automation
        assertNull(off.activeHours); assertNull(off.replyDelaySeconds); assertNull(off.dailySendLimit)
    }

    @Test fun savedPreferencesRoundTripThroughTheForm() {
        val prefs = Preferences(
            version = 3,
            intent = Intent(IntentMode.CUSTOM, "Climbing partner, maybe more"),
            dealbreakers = listOf("smoking"),
            style = StyleProfile(tone = Tone.WARM, styleSamples = listOf("haha")),
            automation = AutomationSettings(AutomationMode.ASSISTED, approvalRequiredFor = listOf(ApprovalReason.USER_RULE)),
            updatedAt = Instant.parse("2026-10-31T18:00:00Z"),
        )
        val form = PreferenceForm.from(prefs)
        val update = valid(form)
        assertEquals(prefs.intent, update.intent)
        assertEquals(prefs.style, update.style)
        // Dealbreakers and system approval reasons are carried, never wiped by a full-replacement PUT.
        assertEquals(listOf("smoking"), update.dealbreakers)
        assertEquals(listOf(ApprovalReason.USER_RULE), update.automation.approvalRequiredFor)
        assertEquals(AutomationMode.ASSISTED, update.automation.mode)
    }
}

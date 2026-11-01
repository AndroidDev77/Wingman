package com.wingman.features.settings

import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.ContactPreferenceOverrideUpdate
import com.wingman.core.model.ContactPreferences
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Humor
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.Tone
import com.wingman.core.ui.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class OverrideRecordingApi(val inner: MockWingmanApi = MockWingmanApi()) : WingmanApi by inner {
    val contactPuts = mutableListOf<Pair<ContactPreferenceOverrideUpdate, Int?>>()
    var userPuts = 0
    var deletes = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun putPreferences(body: PreferencesUpdate, ifMatchVersion: Int?): ApiResult<Preferences> {
        userPuts++
        return inner.putPreferences(body, ifMatchVersion)
    }

    override suspend fun putContactPreferences(contactId: String, body: ContactPreferenceOverrideUpdate, ifMatchVersion: Int?): ApiResult<ContactPreferences> {
        contactPuts += body to ifMatchVersion
        gate?.await()
        return inner.putContactPreferences(contactId, body, ifMatchVersion)
    }

    override suspend fun deleteContactPreferences(contactId: String): ApiResult<Unit> {
        deletes++
        gate?.await()
        return inner.deleteContactPreferences(contactId)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContactOverridesTest {
    private val contact = MockFixtures.CONTACT_SAM

    private val userWide = PreferencesUpdate(
        Intent(IntentMode.SERIOUS),
        StyleProfile(tone = Tone.WARM, humor = Humor.LIGHT, styleSamples = listOf("haha nice")),
        AutomationSettings(AutomationMode.MANUAL, dailySendLimit = 10),
        dealbreakers = listOf("smoking"),
    )

    private suspend fun TestScope.setup(api: OverrideRecordingApi = OverrideRecordingApi()): Pair<OverrideRecordingApi, ContactOverrideEditor> {
        api.inner.putPreferences(userWide, 0)
        val editor = ContactOverrideEditor(this, api, contact, loadChannels = { ChannelInfo.of(MockFixtures.connections) })
        editor.load(); advanceUntilIdle()
        return api to editor
    }

    private val ContactOverrideEditor.c get() = content!!

    @Test fun overrideNeverAltersGlobalSettings() = runTest {
        val (api, editor) = setup()
        val before = api.inner.getPreferences().getOrNull()!!

        editor.edit { it.copy(intentOverridden = true, intentMode = IntentMode.CUSTOM, customDescription = "Climbing buddy first") }
        editor.edit { it.copy(styleOverridden = true, style = StyleForm(tone = Tone.PLAYFUL)) }
        editor.requestAutomationMode(AutomationMode.ASSISTED)
        editor.confirmAutomationMode()
        assertTrue(editor.save()); advanceUntilIdle()

        assertEquals(0, api.userPuts)
        assertEquals(before, api.inner.getPreferences().getOrNull()!!)
        val c = editor.c
        assertEquals(IntentMode.CUSTOM, c.server.effective.intent.mode)
        assertEquals(Tone.PLAYFUL, c.server.effective.style.tone)
        assertEquals(Humor.LIGHT, c.server.effective.style.humor) // inherited per attribute
        assertEquals(AutomationMode.ASSISTED, c.server.effective.automation.mode)
        assertEquals(10, c.server.effective.automation.dailySendLimit) // user-wide only

        // Another contact is untouched.
        val other = api.inner.getContactPreferences(MockFixtures.CONTACT_JORDAN).getOrNull()!!
        assertEquals(IntentMode.SERIOUS, other.effective.intent.mode)
        assertEquals(0, other.override.version)

        // Clearing also leaves global settings alone.
        assertTrue(editor.clear()); advanceUntilIdle()
        assertEquals(0, api.userPuts)
        assertEquals(before, api.inner.getPreferences().getOrNull()!!)
    }

    @Test fun effectiveValuesShowTheirSource() = runTest {
        val (_, editor) = setup()
        val initial = editor.c.effectiveRows.associateBy { it.label }
        assertEquals(ValueSource.USER, initial["Intent"]!!.source)
        assertEquals("Serious relationship", initial["Intent"]!!.value)
        assertEquals(ValueSource.USER, initial["Tone"]!!.source)
        assertEquals(ValueSource.NOT_SET, initial["Punctuation"]!!.source)
        assertEquals(PreferenceLabels.NOT_SET, initial["Punctuation"]!!.value)
        assertFalse(editor.c.hasOverride)

        editor.edit { it.copy(styleOverridden = true, style = StyleForm(tone = Tone.FLIRTY)) }
        editor.save(); advanceUntilIdle()
        val rows = editor.c.effectiveRows.associateBy { it.label }
        assertEquals(EffectiveRow("Tone", "Flirty", ValueSource.CONTACT), rows["Tone"])
        assertEquals(EffectiveRow("Humor", "Light", ValueSource.USER), rows["Humor"])
        assertEquals(ValueSource.USER, rows["Intent"]!!.source)
        assertEquals(EffectiveRow("Style samples", "1 sample", ValueSource.USER), rows["Style samples"])
        assertTrue(editor.c.hasOverride)
    }

    @Test fun defaultsWhenUserNeverSaved() = runTest {
        val api = MockWingmanApi()
        val editor = ContactOverrideEditor(this, api, contact, loadChannels = { ChannelInfo.UNKNOWN })
        editor.load(); advanceUntilIdle()
        val rows = editor.c.effectiveRows.associateBy { it.label }
        assertEquals(ValueSource.DEFAULT, rows["Intent"]!!.source)
        assertEquals(EffectiveRow("Automation", "Manual (off)", ValueSource.DEFAULT), rows["Automation"])
    }

    @Test fun clearFallsBackToUserWide() = runTest {
        val (api, editor) = setup()
        editor.edit { it.copy(intentOverridden = true, intentMode = IntentMode.OPEN) }
        editor.save(); advanceUntilIdle()
        assertEquals(IntentMode.OPEN, editor.c.server.effective.intent.mode)

        assertTrue(editor.clear()); advanceUntilIdle()
        assertEquals(1, api.deletes)
        assertFalse(editor.c.hasOverride)
        assertEquals(IntentMode.SERIOUS, editor.c.server.effective.intent.mode)
        assertEquals(ValueSource.USER, editor.c.effectiveRows.first { it.label == "Intent" }.source)
        assertEquals(OverrideForm(), editor.c.form)
        assertNotNull(editor.c.notice)
    }

    @Test fun turningASectionOffSendsNullToInherit() = runTest {
        val (api, editor) = setup()
        editor.edit { it.copy(intentOverridden = true, intentMode = IntentMode.OPEN) }
        editor.save(); advanceUntilIdle()
        editor.edit { it.copy(intentOverridden = false) }
        editor.save(); advanceUntilIdle()
        assertNull(api.contactPuts.last().first.intent)
        assertEquals(1, api.contactPuts.last().second)
        assertEquals(IntentMode.SERIOUS, editor.c.server.effective.intent.mode)
    }

    @Test fun overrideCustomWithoutDescriptionRejected() = runTest {
        val (api, editor) = setup()
        editor.edit { it.copy(intentOverridden = true, intentMode = IntentMode.CUSTOM, customDescription = "") }
        assertFalse(editor.save()); advanceUntilIdle()
        assertTrue(api.contactPuts.isEmpty())
        assertTrue(FormField.CUSTOM_DESCRIPTION in editor.c.errors)
        // Overriding intent without picking a mode is also invalid.
        editor.edit { it.copy(intentMode = null) }
        assertFalse(editor.save())
        assertTrue(FormField.INTENT_MODE in editor.c.errors)
    }

    @Test fun overrideAutomationNeedsConfirmation() = runTest {
        val (_, editor) = setup()
        editor.edit { it.copy(automationMode = AutomationMode.AUTOPILOT) }
        assertNull(editor.c.form.automationMode)
        editor.requestAutomationMode(AutomationMode.AUTOPILOT)
        assertNull(editor.c.form.automationMode)
        val e = editor.c.pendingAutomation!!.explanation
        assertTrue(e.points.any { it == "Automatic sends happen only on: Simulator." })
        editor.cancelAutomationMode()
        assertNull(editor.c.form.automationMode)
        editor.requestAutomationMode(AutomationMode.MANUAL)
        assertEquals(AutomationMode.MANUAL, editor.c.form.automationMode)
        editor.requestAutomationMode(null)
        assertNull(editor.c.form.automationMode)
    }

    @Test fun overrideConflictReloadsKeepsEditsAndDoesNotOverwrite() = runTest {
        val (api, editor) = setup()
        // Another device sets an override first.
        api.inner.putContactPreferences(contact, ContactPreferenceOverrideUpdate(automationMode = AutomationMode.MANUAL), 0)

        editor.edit { it.copy(intentOverridden = true, intentMode = IntentMode.OPEN) }
        editor.save(); advanceUntilIdle()
        val c = editor.c
        assertNotNull(c.conflictMessage)
        assertTrue("Nothing was overwritten" in c.conflictMessage!!)
        assertEquals(1, c.baseVersion) // reloaded
        assertEquals(AutomationMode.MANUAL, c.server.override.automationMode)
        assertTrue(c.form.intentOverridden) // edits kept
        assertFalse(c.busy)
        val server = api.inner.getContactPreferences(contact).getOrNull()!!
        assertNull(server.override.intent)
        assertEquals(1, api.contactPuts.size)

        editor.dismissConflict()
        editor.save(); advanceUntilIdle()
        assertEquals(1, api.contactPuts.last().second)
        assertEquals(IntentMode.OPEN, api.inner.getContactPreferences(contact).getOrNull()!!.override.intent!!.mode)
    }

    @Test fun overrideConflictDiscardEdits() = runTest {
        val (api, editor) = setup()
        api.inner.putContactPreferences(contact, ContactPreferenceOverrideUpdate(automationMode = AutomationMode.MANUAL), 0)
        editor.edit { it.copy(intentOverridden = true, intentMode = IntentMode.OPEN) }
        editor.save(); advanceUntilIdle()
        editor.discardEdits()
        assertNull(editor.c.conflictMessage)
        assertFalse(editor.c.form.intentOverridden)
        assertEquals(AutomationMode.MANUAL, editor.c.form.automationMode)
    }

    @Test fun doubleTapSaveAndClearSendOneRequest() = runTest {
        val (api, editor) = setup()
        editor.edit { it.copy(intentOverridden = true, intentMode = IntentMode.OPEN) }
        api.gate = CompletableDeferred()
        assertTrue(editor.save())
        assertFalse(editor.save())
        assertFalse(editor.clear()) // a different action is also blocked while one is in flight
        advanceUntilIdle()
        assertTrue(editor.c.busy)
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, api.contactPuts.size)
        assertEquals(0, api.deletes)

        api.gate = CompletableDeferred()
        assertTrue(editor.clear())
        assertFalse(editor.clear())
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, api.deletes)
    }

    @Test fun unknownContactIsNotFoundError() = runTest {
        val editor = ContactOverrideEditor(this, MockWingmanApi(), "0192f3a0-0000-7000-8000-00000000ffff", loadChannels = { ChannelInfo.UNKNOWN })
        editor.load(); advanceUntilIdle()
        val s = editor.state.value
        assertTrue(s is UiState.Error && s.error.code == ErrorCode.NOT_FOUND)
    }

    @Test fun mockMergeMatchesBackendRules() {
        val base = MockFixtures.defaultPreferences.copy(version = 2, style = StyleProfile(tone = Tone.WARM, styleSamples = listOf("a")))
        val o = com.wingman.core.model.ContactPreferenceOverride(version = 1, style = StyleProfile(humor = Humor.WITTY, styleSamples = emptyList()))
        val merged = MockWingmanApi.mergeEffective(base, o)
        assertEquals(Tone.WARM, merged.style.tone)
        assertEquals(Humor.WITTY, merged.style.humor)
        assertEquals(listOf("a"), merged.style.styleSamples) // empty override samples inherit
        assertEquals(2, merged.version) // version stays user-wide
        assertEquals(base, MockWingmanApi.mergeEffective(base, com.wingman.core.model.ContactPreferenceOverride(version = 3)))
    }

    @Test fun contactChoicesAreDistinctAndSorted() = runTest {
        val r = loadContactChoices(MockWingmanApi())
        val names = r.getOrNull()!!.map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
        assertEquals(names.toSet().size, names.size)
        assertTrue("Sam" in names)
        val api = MockWingmanApi(); api.failNextWith()
        assertTrue(loadContactChoices(api) is ApiResult.Failure)
    }
}

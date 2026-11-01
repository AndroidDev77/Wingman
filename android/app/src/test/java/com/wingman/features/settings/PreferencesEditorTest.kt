package com.wingman.features.settings

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Preferences
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.Tone
import com.wingman.core.model.Verification
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
import java.io.IOException

/** Counts PUTs and can hold a PUT open to simulate a slow network. */
private class PutRecordingApi(val inner: MockWingmanApi = MockWingmanApi()) : WingmanApi by inner {
    val puts = mutableListOf<Pair<PreferencesUpdate, Int?>>()
    var gate: CompletableDeferred<Unit>? = null
    /** Simulates "server committed, response lost": the write happens, the client sees a network error. */
    var loseNextResponse = false

    override suspend fun putPreferences(body: PreferencesUpdate, ifMatchVersion: Int?): ApiResult<Preferences> {
        puts += body to ifMatchVersion
        gate?.await()
        val r = inner.putPreferences(body, ifMatchVersion)
        if (loseNextResponse) {
            loseNextResponse = false
            return ApiResult.Failure(ApiError.Network(IOException("connection reset")))
        }
        return r
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesEditorTest {

    private fun TestScope.loaded(api: WingmanApi, channels: ChannelInfo? = null): PreferencesEditor {
        val editor = if (channels == null) PreferencesEditor(this, api) else PreferencesEditor(this, api, loadChannels = { channels })
        editor.load(); advanceUntilIdle()
        return editor
    }

    private val PreferencesEditor.c get() = content!!

    @Test fun automationDefaultsToManual() = runTest {
        val api = PutRecordingApi()
        val editor = loaded(api)
        assertEquals(AutomationMode.MANUAL, editor.c.form.automation.mode)
        editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        assertTrue(editor.save()); advanceUntilIdle()
        assertEquals(AutomationMode.MANUAL, api.puts.single().first.automation.mode)
        assertEquals(AutomationMode.MANUAL, api.inner.getPreferences().getOrNull()!!.automation.mode)
    }

    @Test fun automationChangesOnlyOnExplicitConfirmedAction() = runTest {
        val editor = loaded(MockWingmanApi())
        // A generic form edit can't switch automation on.
        editor.editAutomation { it.copy(mode = AutomationMode.AUTOPILOT, dailySendLimit = "5") }
        assertEquals(AutomationMode.MANUAL, editor.c.form.automation.mode)
        assertEquals("5", editor.c.form.automation.dailySendLimit)

        // Requesting a mode only shows the explanation.
        editor.requestAutomationMode(AutomationMode.ASSISTED)
        assertEquals(AutomationMode.MANUAL, editor.c.form.automation.mode)
        assertEquals(AutomationMode.ASSISTED, editor.c.pendingAutomation!!.explanation.mode)
        editor.cancelAutomationMode()
        assertNull(editor.c.pendingAutomation)
        assertEquals(AutomationMode.MANUAL, editor.c.form.automation.mode)

        editor.requestAutomationMode(AutomationMode.AUTOPILOT)
        editor.confirmAutomationMode()
        assertEquals(AutomationMode.AUTOPILOT, editor.c.form.automation.mode)
        // Confirming again with nothing pending does nothing.
        editor.confirmAutomationMode()
        assertEquals(AutomationMode.AUTOPILOT, editor.c.form.automation.mode)

        // Turning automation off needs no confirmation.
        editor.requestAutomationMode(AutomationMode.MANUAL)
        assertEquals(AutomationMode.MANUAL, editor.c.form.automation.mode)
        assertNull(editor.c.pendingAutomation)
    }

    @Test fun autopilotExplanationNeverClaimsUnverifiedChannelsSend() = runTest {
        // Mock fixtures: SMS send UNVERIFIED, Simulator send VERIFIED, Notifications send UNSUPPORTED.
        val editor = loaded(MockWingmanApi())
        editor.requestAutomationMode(AutomationMode.AUTOPILOT)
        val e = editor.c.pendingAutomation!!.explanation
        assertTrue(e.points.any { it == "Automatic sends happen only on: Simulator." })
        assertTrue(e.points.any { "Android SMS" in it && "UNVERIFIED" in it })
        assertFalse(e.points.any { "Android SMS" in it && "sending VERIFIED" in it })
        assertNull(e.warning)

        val noneVerified = ChannelInfo(true, listOf(ChannelSend("Android SMS", Verification.UNVERIFIED, connected = true)))
        val w = AutomationExplainer.explain(AutomationMode.AUTOPILOT, AutomationForm(), noneVerified)
        assertNotNull(w.warning)
        assertTrue(w.points.first().contains("You have none right now"))

        // A VERIFIED but disconnected channel can't send either.
        val disconnected = ChannelInfo(true, listOf(ChannelSend("Simulator", Verification.VERIFIED, connected = false)))
        assertTrue(disconnected.autoSendChannels.isEmpty())

        // Unknown channels (load failed) are never treated as able to send.
        val unknown = AutomationExplainer.explain(AutomationMode.AUTOPILOT, AutomationForm(), ChannelInfo.UNKNOWN)
        assertNotNull(unknown.warning)
        assertTrue(ChannelInfo.UNKNOWN.autoSendChannels.isEmpty())

        val assisted = AutomationExplainer.explain(AutomationMode.ASSISTED, AutomationForm(), noneVerified)
        assertTrue("Nothing is sent automatically" in assisted.summary)
    }

    @Test fun channelLoadFailureGivesUnknownChannels() = runTest {
        val api = object : WingmanApi by MockWingmanApi() {
            override suspend fun listConnections(page: com.wingman.core.api.PageRequest) =
                ApiResult.Failure(ApiError.Network(IOException("down")))
        }
        val editor = loaded(api)
        assertEquals(ChannelInfo.UNKNOWN, editor.c.channels)
    }

    @Test fun loadErrorThenRetry() = runTest {
        val api = MockWingmanApi()
        api.failNextWith()
        val editor = PreferencesEditor(this, api, loadChannels = { ChannelInfo.UNKNOWN })
        editor.load(); advanceUntilIdle()
        val s = editor.state.value
        assertTrue(s is UiState.Error && s.error.isNetwork)
        assertFalse(editor.save())
        editor.load(); advanceUntilIdle()
        assertTrue(editor.state.value is UiState.Content)
    }

    @Test fun invalidFormSendsNothing() = runTest {
        val api = PutRecordingApi()
        val editor = loaded(api)
        editor.edit { it.copy(intentMode = IntentMode.CUSTOM, customDescription = " ") }
        assertFalse(editor.save()); advanceUntilIdle()
        assertTrue(api.puts.isEmpty())
        assertTrue(FormField.CUSTOM_DESCRIPTION in editor.c.errors)
        // Fixing the field clears the error live.
        editor.edit { it.copy(customDescription = "Something real") }
        assertTrue(editor.c.errors.isEmpty())
    }

    @Test fun saveUsesIfMatchAndAdvancesVersion() = runTest {
        val api = PutRecordingApi()
        val editor = loaded(api)
        editor.edit { it.copy(intentMode = IntentMode.SERIOUS) }
        assertTrue(editor.c.isDirty)
        var saved: Preferences? = null
        editor.save { saved = it }; advanceUntilIdle()
        assertEquals(0, api.puts[0].second)
        assertEquals(1, saved!!.version)
        assertEquals(SaveStatus.Saved(1), editor.c.saveStatus)
        assertFalse(editor.c.isDirty)

        editor.editStyle { it.copy(tone = Tone.WARM) }
        editor.save(); advanceUntilIdle()
        assertEquals(1, api.puts[1].second)
        assertEquals(2, editor.c.baseVersion)
    }

    @Test fun doubleTapSaveSendsOneRequest() = runTest {
        val api = PutRecordingApi()
        val editor = loaded(api)
        editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        api.gate = CompletableDeferred()
        assertTrue(editor.save())
        assertFalse(editor.save())
        assertFalse(editor.save())
        advanceUntilIdle()
        assertEquals(SaveStatus.Saving, editor.c.saveStatus)
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, api.puts.size)
        assertEquals(1, api.inner.calls.count { it == "putPreferences" })
        assertEquals(SaveStatus.Saved(1), editor.c.saveStatus)
    }

    @Test fun preconditionFailedReloadsAndKeepsEditsWithoutOverwriting() = runTest {
        val api = PutRecordingApi()
        val editor = loaded(api)
        // Another device saves first.
        val other = PreferencesUpdate(Intent(IntentMode.CASUAL_DATING), StyleProfile(tone = Tone.DIRECT), AutomationSettings(AutomationMode.MANUAL))
        api.inner.putPreferences(other, ifMatchVersion = 0)

        editor.edit { it.copy(intentMode = IntentMode.SERIOUS) }
        editor.save(); advanceUntilIdle()

        val c = editor.c
        assertNotNull(c.conflict)
        assertEquals(1, c.conflict!!.latest!!.version)
        assertTrue("Nothing was overwritten" in c.conflict!!.message)
        assertEquals(IntentMode.SERIOUS, c.form.intentMode) // edits kept
        assertEquals(SaveStatus.Idle, c.saveStatus)
        // Server still has the other device's version: nothing silently overwritten.
        assertEquals(IntentMode.CASUAL_DATING, api.inner.getPreferences().getOrNull()!!.intent.mode)
        // Saving is blocked until the user decides.
        assertFalse(editor.save())
        assertEquals(1, api.puts.size)

        editor.keepMyEdits()
        assertNull(editor.c.conflict)
        assertEquals(1, editor.c.baseVersion)
        editor.save(); advanceUntilIdle()
        assertEquals(1, api.puts[1].second)
        assertEquals(IntentMode.SERIOUS, api.inner.getPreferences().getOrNull()!!.intent.mode)
        assertEquals(2, editor.c.baseVersion)
    }

    @Test fun preconditionFailedUseLatestDiscardsEdits() = runTest {
        val api = PutRecordingApi()
        val editor = loaded(api)
        api.inner.putPreferences(PreferencesUpdate(Intent(IntentMode.OPEN), StyleProfile(), AutomationSettings(AutomationMode.MANUAL)), 0)
        editor.edit { it.copy(intentMode = IntentMode.SERIOUS) }
        editor.save(); advanceUntilIdle()
        editor.useLatest()
        assertNull(editor.c.conflict)
        assertEquals(IntentMode.OPEN, editor.c.form.intentMode)
        assertEquals(1, editor.c.baseVersion)
        assertEquals(1, api.puts.size)
    }

    @Test fun lostResponseRetryThat412sIsRecognisedAsOurOwnSave() = runTest {
        val api = PutRecordingApi()
        val editor = loaded(api)
        editor.edit { it.copy(intentMode = IntentMode.CASUAL_PHYSICAL) }
        api.loseNextResponse = true
        // ActionRunner retries the network error with the same If-Match; the server already moved
        // to version 1, so the retry is 412. The editor must see that version 1 is exactly our save.
        editor.save(); advanceUntilIdle()
        assertEquals(2, api.puts.size)
        assertNull(editor.c.conflict)
        assertEquals(SaveStatus.Saved(1), editor.c.saveStatus)
        assertEquals(1, editor.c.baseVersion)
    }

    @Test fun nonConflictFailureIsShownAndRetryable() = runTest {
        val api = MockWingmanApi()
        val editor = loaded(api, ChannelInfo.UNKNOWN)
        editor.edit { it.copy(intentMode = IntentMode.OPEN) }
        api.failNextWith((MockWingmanApi.problem(422, ErrorCode.VALIDATION_ERROR, "bad") as ApiResult.Failure).error)
        editor.save(); advanceUntilIdle()
        val status = editor.c.saveStatus as SaveStatus.Failed
        assertEquals(ErrorCode.VALIDATION_ERROR, status.error.code)
        assertTrue(editor.save()); advanceUntilIdle()
        assertEquals(SaveStatus.Saved(1), editor.c.saveStatus)
    }
}

package com.wingman.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wingman.BuildConfig
import com.wingman.MainActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * WING-025, ticket criterion "Android app drives backend and displays action logs".
 *
 * Runs on the CI emulator (.github/workflows/e2e.yml, job `emulator`) against the real backend
 * on the runner (scripts/dev-run.sh: API + worker, dev auth stub, labeled dev mock LLM, mock
 * connector), reached at http://10.0.2.2:8000/ from the emulator. The app is a debug build with
 * `-Pwingman.useMockApi=false`, so every screen below talks to that backend over HTTP.
 *
 * One test, in order, as a user would: sign in with a dev-stub token (debug panel) -> onboarding
 * (choose intent, assisted mode, save) -> a contact's message arrives (injected over the API, the
 * contact's side) -> it shows in the Inbox -> approve the draft in Approvals -> the Activity tab
 * shows "Queued, not sent yet" and then "Sent (delivery not confirmed)" from the server's audit
 * log -> take over the contact in Controls and see it confirmed. Each UI step is cross-checked
 * against the server's own state.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppDrivesBackendE2eTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val backend = E2eBackend()

    @Test fun appDrivesBackendAndShowsActionLog() {
        assertFalse("must be built with -Pwingman.useMockApi=false", BuildConfig.USE_MOCK_API)
        assertTrue("dev sign-in exists only in debug builds", BuildConfig.DEBUG)
        val me = backend.me()
        val userId = me.getString("id")
        assertEquals("the user must be new (never onboarded)", 0, backend.preferences().getInt("version"))

        // 1. Sign in through the debug-only developer panel (token minted by the backend CLI).
        compose.signInWithDevToken(E2eArgs.token)

        // 2. Onboarding opens on its own for a never-saved user (server version 0).
        compose.waitForText("What are you looking for?")
        compose.onNodeWithText("Serious relationship").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.waitForText("How do you text?")
        compose.onNodeWithText("Skip").performClick()
        compose.waitForText("How much should Wingman do?")
        compose.onNodeWithText("Assisted").performClick()
        compose.waitForText("Turn on Assisted?")
        compose.onNodeWithText("Turn on Assisted").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.waitForText("Review and save")
        compose.onNodeWithText("Save and finish").performClick()
        compose.waitForText("Inbox") // the wizard hands over to the Inbox
        val prefs = eventually(what = "saved preferences") { backend.preferences().takeIf { it.getInt("version") >= 1 } }
        assertEquals("serious", prefs.getJSONObject("intent").getString("mode"))
        assertEquals("assisted", prefs.getJSONObject("automation").getString("mode"))

        // 3. A contact writes (the contact's side is simulated over the API).
        val contact = "Emu${E2eArgs.runId}"
        val conn = backend.pairMock("Android E2E simulator")
        backend.injectInbound(conn, userId, contact, "Hi from the emulator test!")
        val conv = eventually(what = "conversation on the server") { backend.conversationFor(contact) }
        val conversationId = conv.getString("id")
        val contactId = conv.getJSONObject("contact").getString("id")
        eventually(what = "pending approval on the server") { backend.approvals("pending", conversationId).firstOrNull() }

        // 4. The Inbox shows it (Inbox re-reads the server on Refresh).
        compose.onAllNodes(hasText("Inbox") and hasClickAction())[0].performClick()
        compose.refreshUntil(contact)
        compose.waitForText("Hi from the emulator test!")

        // 5. Approvals: the draft is there; approve it.
        compose.onAllNodes(hasText("Approvals") and hasClickAction())[0].performClick()
        compose.refreshUntil(contact)
        compose.waitForText("[dev mock]", substring = true)
        compose.waitForText("Nothing is sent until you approve.")
        compose.onNodeWithText("Approve").performClick()
        compose.waitForText("Approved. It shows as sent only after the messaging app confirms it.")
        val sent = eventually(what = "server reports the reply sent") {
            backend.messages(conversationId).firstOrNull { it.getString("direction") == "outbound" && it.getString("delivery_state") == "sent" }
        }
        assertEquals("approved_draft", sent.getString("origin"))

        // 6. Activity: the audit log FROM THE SERVER shows queued, then sent.
        compose.onNode(tab("Activity")).performClick()
        compose.refreshUntil("Sent (delivery not confirmed)")
        compose.seeInList("Sent (delivery not confirmed)")
        compose.seeInList("Handed to the messaging app, not confirmed yet")
        compose.seeInList("Queued, not sent yet")
        compose.seeInList("Draft approved (not sent yet)")
        compose.seeInList("Draft proposed for your approval")
        compose.seeInList("Message received")
        val types = backend.actions(conversationId = conversationId).map { it.getString("type") }
        assertTrue(types.containsAll(listOf("send_queued", "send_acknowledged", "approval_approved")))

        // 7. Controls: take over the contact; the switch moves only after the server confirms.
        compose.onNode(tab("Controls")).performClick()
        compose.seeInList("I'm taking over")
        compose.seeInList(contact)
        // The switch in the same row as "I'm taking over" (this user has exactly one contact).
        compose.onNode(switchNear("I'm taking over")).performClick()
        compose.waitForText("Take over with $contact?")
        compose.onNode(hasText("Take over") and hasClickAction()).performClick() // dialog confirm
        compose.waitForText("You've taken over.", substring = true)
        compose.waitForText("You're in control: Wingman is off for this person")
        val state: JSONObject = eventually(what = "server reports taken_over") {
            backend.contactAutomation(contactId).takeIf { it.getString("status") == "taken_over" }
        }
        assertEquals("taken_over", state.getString("status"))
        assertTrue(backend.actions(contactId = contactId).any { it.getString("type") == "automation_takeover" })
    }
}

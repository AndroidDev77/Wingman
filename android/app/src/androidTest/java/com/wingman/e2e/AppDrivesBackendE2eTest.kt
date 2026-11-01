package com.wingman.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.wingman.BuildConfig
import com.wingman.MainActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WING-025, ticket criterion "Android app drives backend and displays action logs".
 *
 * Runs on the CI emulator (.github/workflows/e2e.yml, job `emulator`) against the real backend
 * on the runner (scripts/dev-run.sh: API + worker, dev auth stub, labeled dev mock LLM, mock
 * connector), reached at http://10.0.2.2:8000/ from the emulator. The app is the unmodified
 * debug build with `-Pwingman.useMockApi=false`, launched normally and driven through the
 * accessibility tree with UiAutomator (see [E2eDevice] for why not the Compose test rule).
 *
 * One test, in order, as a user would: sign in with a dev-stub token (debug panel) -> onboarding
 * (choose intent, assisted mode, save) -> a contact's message arrives (injected over the API, the
 * contact's side) -> it shows in the Inbox -> approve the draft in Approvals -> the Activity tab
 * shows "Queued, not sent yet" and "Sent (delivery not confirmed)" from the server's audit log ->
 * take over the contact in Controls and see it confirmed. Each UI step is cross-checked against
 * the server's own state.
 */
@RunWith(AndroidJUnit4::class)
class AppDrivesBackendE2eTest {

    private val backend = E2eBackend()
    private val ui = E2eDevice()

    private fun log(msg: String) = println("WING025-APP $msg")

    @Test fun appDrivesBackendAndShowsActionLog() {
        assertFalse("must be built with -Pwingman.useMockApi=false", BuildConfig.USE_MOCK_API)
        assertTrue("dev sign-in exists only in debug builds", BuildConfig.DEBUG)
        val userId = backend.me().getString("id")
        assertEquals("the user must be new (never onboarded)", 0, backend.preferences().getInt("version"))

        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. Sign in through the debug-only developer panel (token minted by the backend CLI).
            ui.signInWithDevToken(E2eArgs.token)

            // 2. Onboarding opens on its own for a never-saved user (server version 0).
            ui.waitText("What are you looking for?")
            log("signed in; onboarding opened")
            ui.click(By.textStartsWith("Serious relationship"), "intent Serious relationship")
            ui.clickText("Next")
            ui.waitText("How do you text?")
            ui.clickText("Skip")
            ui.waitText("How much should Wingman do?")
            ui.click(By.textStartsWith("Assisted"), "mode Assisted")
            ui.waitText("Turn on Assisted?")
            ui.clickText("Turn on Assisted")
            ui.clickText("Next")
            ui.waitText("Review and save")
            ui.clickText("Save and finish")
            ui.waitFor(By.textContains("Inbox"), "Inbox after onboarding")
            val prefs = eventually(what = "saved preferences") { backend.preferences().takeIf { it.getInt("version") >= 1 } }
            assertEquals("serious", prefs.getJSONObject("intent").getString("mode"))
            assertEquals("assisted", prefs.getJSONObject("automation").getString("mode"))
            log("onboarding saved: version=${prefs.getInt("version")} intent=serious mode=assisted")

            // 3. A contact writes (the contact's side is simulated over the API).
            val contact = "Emu${E2eArgs.runId.filter { it.isLetterOrDigit() }.takeLast(10)}"
            val conn = backend.pairMock("Android E2E simulator")
            backend.injectInbound(conn, userId, contact, "Hi from the emulator test!")
            val conv = eventually(what = "conversation on the server") { backend.conversationFor(contact) }
            val conversationId = conv.getString("id")
            val contactId = conv.getJSONObject("contact").getString("id")
            eventually(what = "pending approval on the server") { backend.approvals("pending", conversationId).firstOrNull() }

            // 4. The Inbox shows it (the Inbox re-reads the server on Refresh).
            ui.openTab("Inbox")
            ui.refreshUntil(By.textContains(contact), "inbox row for $contact")
            ui.waitFor(By.textContains("Hi from the emulator test!"), "message preview in the inbox")
            log("inbox shows $contact with the message preview")

            // 5. Approvals: the draft is there; approve it.
            ui.openTab("Approvals")
            ui.refreshUntil(By.textContains("[dev mock]"), "the draft in the approval queue")
            ui.waitText("Nothing is sent until you approve.")
            ui.clickText("Approve")
            ui.waitText("Approved. It shows as sent only after the messaging app confirms it.")
            val sent = eventually(what = "server reports the reply sent") {
                backend.messages(conversationId).firstOrNull { it.getString("direction") == "outbound" && it.getString("delivery_state") == "sent" }
            }
            assertEquals("approved_draft", sent.getString("origin"))
            log("approved in the app; server message ${sent.getString("id")} is sent")

            // 6. Activity: the audit log FROM THE SERVER shows queued and then sent.
            ui.openInnerTab("Activity")
            ui.refreshUntil(By.text("Sent (delivery not confirmed)"), "audit row: sent")
            for (row in listOf(
                "Handed to the messaging app, not confirmed yet",
                "Queued, not sent yet",
                "Draft approved (not sent yet)",
                "Draft proposed for your approval",
                "Message received",
            )) {
                ui.scrollTo(By.text(row), "audit row \"$row\"")
            }
            val types = backend.actions(conversationId = conversationId).map { it.getString("type") }
            assertTrue(types.containsAll(listOf("send_queued", "send_attempted", "send_acknowledged", "approval_approved")))
            log("activity tab shows: message received, proposed, approved, queued, handed over, sent; server audit=$types")

            // 7. Controls: take over the contact; the switch moves only after the server confirms.
            ui.openInnerTab("Controls")
            ui.scrollTo(By.text(contact), "controls card for $contact")
            ui.scrollTo(By.text("I'm taking over"), "takeover switch")
            ui.switchBeside("I'm taking over").click()
            ui.waitText("Take over with $contact?")
            // "Take over" is also a heading in the Controls explainer: tap the dialog's button.
            ui.clickBelow("Take over with $contact?", "Take over")
            ui.waitFor(By.textStartsWith("You've taken over."), "takeover confirmation")
            ui.waitText("You're in control: Wingman is off for this person")
            val state: JSONObject = eventually(what = "server reports taken_over") {
                backend.contactAutomation(contactId).takeIf { it.getString("status") == "taken_over" }
            }
            assertEquals("taken_over", state.getString("status"))
            assertTrue(backend.actions(contactId = contactId).any { it.getString("type") == "automation_takeover" })
            log("takeover confirmed in the app and on the server (status=taken_over, audit automation_takeover)")
        }
    }
}

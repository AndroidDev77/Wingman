package com.wingman.integrations.screenreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The chat screen is context only: sender side, text and order; never a message source. */
class ChatScreenReaderTest {
    @Test fun `visible lines are read with their side and order`() {
        val chat = ChatScreenReader.read(chatScreen(), ScreenProfile.HINGE)
        assertEquals(Sentinels.NAME, chat.headerName)
        assertEquals(
            listOf(ChatSide.THEIRS to Sentinels.THEIRS_1, ChatSide.MINE to Sentinels.MINE_1, ChatSide.THEIRS to Sentinels.THEIRS_2),
            chat.lines.map { it.side to it.text },
        )
        assertEquals(listOf(0, 1, 2), chat.lines.map { it.order })
        // Not the half-typed input, not a timestamp, not the app's own labels.
        val texts = chat.lines.map { it.text }
        assertFalse(texts.contains(Sentinels.TYPING))
        assertFalse(texts.any { it == "10:42" || it == "Send" || it == "Chat" || it == "Profile" })
    }

    @Test fun `side is unknown without usable bounds`() {
        assertEquals(ChatSide.UNKNOWN, ChatScreenReader.side(NodeBounds.EMPTY, NodeBounds(0, 0, 1080, 2400)))
        assertEquals(ChatSide.UNKNOWN, ChatScreenReader.side(NodeBounds(10, 0, 20, 5), NodeBounds.EMPTY))
        assertEquals(ChatSide.UNKNOWN, ChatScreenReader.side(NodeBounds(100, 0, 980, 5), NodeBounds(0, 0, 1080, 2400)))
    }

    @Test fun `message view ids from the app definition are used when present`() {
        val p = ScreenProfile.HINGE.copy(messageTextIds = setOf("msg_body"), chatHeaderNameIds = setOf("header_name"))
        val s = screen(
            ScreenReaderApps.HINGE, 1,
            label("Something Else", 60),
            label(Sentinels.NAME, 120, id = "co.hinge.app:id/header_name"),
            label("A system banner that is not a message", 300),
            label(Sentinels.THEIRS_1, 600, id = "co.hinge.app:id/msg_body", left = 40, right = 700),
            ScreenNode(className = "android.widget.EditText", editable = true),
        )
        val chat = ChatScreenReader.read(s, p)
        assertEquals(Sentinels.NAME, chat.headerName)
        assertEquals(listOf(Sentinels.THEIRS_1), chat.lines.map { it.text })
    }

    @Test fun `a chat screen creates no profile - no upload - no stored message`() {
        val w = ReaderWorld()
        repeat(3) { assertEquals(SnapshotOutcome.CHAT_CONTEXT, w.core.onSnapshot(chatScreen(at = 1_000L + it))) }
        w.core.flush()
        w.core.onIdle(10_000_000)
        assertTrue(w.profiles.all().isEmpty())
        assertTrue(w.captured.isEmpty())
        assertEquals(3, w.core.lastChatContext()!!.lines.size)
        // The chat text is held in memory only: nothing of it is written to the state file.
        for (s in listOf(Sentinels.THEIRS_1, Sentinels.MINE_1, Sentinels.THEIRS_2, Sentinels.TYPING)) assertFalse(w.storedText().contains(s))
        assertTrue(w.store.keys("").none { it.startsWith(CapturedProfileStore.PREFIX) })
    }

    private fun source(name: String): String {
        val dir = File("src").let { if (it.isDirectory) File(".") else File("android/app") }
        return File(dir, "src/experimental/java/com/wingman/integrations/screenreader/$name").readText()
    }

    @Test fun `static - the chat reader and the core cannot create messages or trigger drafts`() {
        val forbidden = listOf(
            "WingmanApi", "ingestConnectorEvent", "createDraft", "sendMessage", "connector_event", "message.received", "Envelope",
            "NotificationInboxEvent", "NotificationUploadQueue", "claimOutboxInstructions", "kotlinx.coroutines",
        )
        for (file in listOf("ChatScreenReader.kt", "ScreenReaderCore.kt", "ProfileExtractor.kt", "ScreenClassifier.kt", "MatchIdentity.kt", "CapturedProfiles.kt", "ScreenModel.kt", "ScreenInspector.kt")) {
            val code = source(file)
            for (word in forbidden) assertFalse("$file mentions $word", code.contains(word))
        }
        // The only file that talks to the backend makes exactly these calls: no event, message or draft route.
        val calls = Regex("""\bapi\.(\w+)\(""").findAll(source("ProfileSync.kt")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("importProfile", "listConversations", "putConversationProfileLink", "deleteConversationProfileLink", "deleteProfile"), calls)
        // ChatContextSnapshot is used only where it is read and where the match key is derived.
        val dir = File("src").let { if (it.isDirectory) File(".") else File("android/app") }
        val users = File(dir, "src/experimental/java/com/wingman/integrations/screenreader").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && it.readText().contains("ChatContextSnapshot") }.map { it.name }.toSet()
        assertEquals(setOf("ChatScreenReader.kt", "ScreenReaderCore.kt"), users)
    }
}

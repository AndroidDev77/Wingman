package com.wingman.integrations.screenreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0008 identity rules for captured profiles: a stable ID is an identity, a name never is. */
class MatchIdentityTest {
    private fun conv(id: String, name: String, thread: String? = "thread-$id", platform: String = "hinge") = ConversationIdentity(id, platform, thread, name)

    @Test fun `a stable id is unambiguous - a name alone is always ambiguous`() {
        assertEquals(MatchKey(MatchKeyKind.STABLE_ID, "match-77", Sentinels.NAME, ambiguous = false), MatchKeys.derive("match-77", Sentinels.NAME, null))
        assertEquals(MatchKey(MatchKeyKind.NAME_ONLY, null, Sentinels.NAME, ambiguous = true), MatchKeys.derive(null, Sentinels.NAME, null))
        assertEquals(MatchKey(MatchKeyKind.NAME_ONLY, null, Sentinels.NAME, ambiguous = true), MatchKeys.derive(null, Sentinels.NAME, Sentinels.OTHER_NAME))
        assertEquals(MatchKey(MatchKeyKind.NONE, null, null, ambiguous = true), MatchKeys.derive(null, null, Sentinels.NAME))
        assertEquals(MatchKeyKind.NONE, MatchKeys.derive("  ", " ", null).kind)
    }

    @Test fun `a name with the chat screen's context is ambiguous when two matches could collide`() {
        val once = listOf(listOf(Sentinels.NAME, Sentinels.OTHER_NAME), listOf(Sentinels.NAME))
        assertEquals(MatchKey(MatchKeyKind.NAME_AND_CHAT, null, Sentinels.NAME, ambiguous = false), MatchKeys.derive(null, Sentinels.NAME, " ${Sentinels.NAME.uppercase()} ", once))
        // Two rows of the match list, or two conversations, carry the same name.
        val twiceInList = listOf(listOf(Sentinels.NAME, Sentinels.NAME.lowercase()))
        assertTrue(MatchKeys.derive(null, Sentinels.NAME, Sentinels.NAME, twiceInList).ambiguous)
        assertTrue(MatchKeys.derive(null, Sentinels.NAME, Sentinels.NAME, once, sameNameOtherProfile = true).ambiguous)
    }

    @Test fun `the core marks the key from the chat seen in the same viewing`() {
        val alone = ReaderWorld()
        alone.core.onSnapshot(profileTop())
        alone.core.flush()
        assertEquals(MatchKey(MatchKeyKind.NAME_ONLY, null, Sentinels.NAME, ambiguous = true), alone.profiles.all().single().matchKey)

        val withChat = ReaderWorld()
        withChat.core.onSnapshot(chatScreen(at = 500))
        withChat.core.onSnapshot(profileTop(at = 1_000))
        withChat.core.onSnapshot(chatScreen(at = 1_500))
        assertEquals(MatchKey(MatchKeyKind.NAME_AND_CHAT, null, Sentinels.NAME, ambiguous = false), withChat.profiles.all().single().matchKey)

        // Two conversations with that name exist: the same viewing is ambiguous.
        val twins = ReaderWorld(conversationNames = { listOf(Sentinels.NAME, Sentinels.NAME) })
        twins.core.onSnapshot(profileTop(at = 1_000))
        twins.core.onSnapshot(chatScreen(at = 1_500))
        assertEquals(MatchKey(MatchKeyKind.NAME_AND_CHAT, null, Sentinels.NAME, ambiguous = true), twins.profiles.all().single().matchKey)

        // The chat of somebody else is no context.
        val otherChat = ReaderWorld()
        otherChat.core.onSnapshot(profileTop(at = 1_000))
        otherChat.core.onSnapshot(chatScreen(at = 1_500, name = Sentinels.OTHER_NAME))
        assertEquals(MatchKeyKind.NAME_ONLY, otherChat.profiles.all().single().matchKey.kind)
    }

    @Test fun `two profiles with the same name are never merged by name alone`() {
        val w = ReaderWorld()
        w.core.onSnapshot(chatScreen(at = 500))
        w.core.onSnapshot(profileTop(at = 1_000))
        w.core.onSnapshot(chatScreen(at = 1_500))
        assertFalse(w.profiles.all().single().matchKey.ambiguous)
        // Another person with the same name and different prompts.
        val namesake = screen(
            ScreenReaderApps.HINGE, 5_000,
            label(Sentinels.NAME, 150, heading = true, height = 80),
            promptCard(Sentinels.Q_SIMPLE, "A different synthetic answer.", 400),
        )
        w.core.onSnapshot(namesake)
        w.core.onSnapshot(chatScreen(at = 5_500))
        val all = w.profiles.all()
        assertEquals(2, all.size)
        assertTrue("both names stopped being usable as a key", all.all { it.matchKey.ambiguous })
        assertEquals(2, all.map { it.localId }.toSet().size)
        assertEquals(setOf(1, 2), all.map { it.prompts.size }.toSet())
    }

    @Test fun `seeing the same profile again updates it instead of adding a copy`() {
        val w = ReaderWorld()
        w.core.onSnapshot(profileTop(at = 1_000))
        w.core.flush()
        val first = w.profiles.all().single()
        assertEquals(2, first.prompts.size)
        // Uploaded in the meantime.
        w.profiles.save(first.copy(needsUpload = false, uploadState = UploadState.UPLOADED, serverProfileId = "server-1", linkedConversationId = "conv-1"))
        // Same name, one identical prompt, plus new content.
        w.core.onSnapshot(profileTop(at = 900_000))
        w.core.onSnapshot(profileScrolled(at = 901_000))
        w.core.flush()
        val again = w.profiles.all().single()
        assertEquals(first.localId, again.localId)
        assertEquals(4, again.prompts.size)
        assertEquals(Sentinels.A_SHOWER, again.prompts.first { it.question == Sentinels.Q_SHOWER }.answer)
        assertTrue("changed content is uploaded again", again.needsUpload)
        assertEquals("server-1", again.serverProfileId)
        assertEquals("conv-1", again.linkedConversationId)
        // An identical third viewing changes nothing and triggers no upload.
        w.profiles.save(again.copy(needsUpload = false))
        val before = w.captured.size
        w.core.onSnapshot(profileTop(at = 1_800_000))
        w.core.flush()
        assertFalse(w.profiles.all().single().needsUpload)
        assertEquals(before, w.captured.size)
    }

    @Test fun `linking is automatic only for a stable id that is exactly one conversation's thread id`() {
        val conversations = listOf(conv("c1", Sentinels.NAME, thread = "match-77"), conv("c2", Sentinels.OTHER_NAME, thread = "match-78"), conv("c3", Sentinels.NAME, thread = "match-77", platform = "tinder"))
        val stable = MatchKey(MatchKeyKind.STABLE_ID, "match-77", Sentinels.NAME, ambiguous = false)
        assertEquals(LinkDecision.Auto("c1"), MatchLinker.decide(stable, "hinge", conversations))
        // No conversation with that ID, or two of them: ask.
        assertTrue(MatchLinker.decide(stable.copy(stableId = "match-00"), "hinge", conversations) is LinkDecision.Ask)
        assertTrue(MatchLinker.decide(stable, "hinge", conversations + conv("c4", "X", thread = "match-77")) is LinkDecision.Ask)
        assertTrue(MatchLinker.decide(stable.copy(ambiguous = true), "hinge", conversations) is LinkDecision.Ask)
    }

    @Test fun `a name never links by itself - it only orders the picker`() {
        val conversations = listOf(conv("c2", Sentinels.OTHER_NAME), conv("c1", Sentinels.NAME), conv("c9", Sentinels.NAME, platform = "sms"))
        for (key in listOf(
            MatchKey(MatchKeyKind.NAME_AND_CHAT, null, Sentinels.NAME, ambiguous = false), // exactly one conversation has this name
            MatchKey(MatchKeyKind.NAME_ONLY, null, Sentinels.NAME, ambiguous = true),
            MatchKey(MatchKeyKind.NONE, null, null, ambiguous = true),
        )) {
            val d = MatchLinker.decide(key, "hinge", conversations)
            assertTrue(key.toString(), d is LinkDecision.Ask)
            d as LinkDecision.Ask
            assertEquals("other platforms are not offered", setOf("c1", "c2"), d.candidates.map { it.conversation.conversationId }.toSet())
            if (key.displayName != null) {
                assertEquals("c1", d.candidates.first().conversation.conversationId)
                assertEquals(listOf(true, false), d.candidates.map { it.sameName })
            }
        }
        // A name equal to a thread ID is still not a stable ID.
        val tricky = MatchKey(MatchKeyKind.NAME_AND_CHAT, null, "thread-c1", ambiguous = false)
        assertTrue(MatchLinker.decide(tricky, "hinge", listOf(conv("c1", "thread-c1"))) is LinkDecision.Ask)
    }

    @Test fun `a stable id from the tree becomes the profile's external id`() {
        val withStable = ScreenProfile.HINGE.copy(stableIdIds = setOf("subject_id"))
        val w = ReaderWorld(screenProfiles = { withStable })
        val top = profileTop()
        w.core.onSnapshot(top.copy(root = top.root.copy(children = top.root.children + label("match-77", 2300, id = "co.hinge.app:id/subject_id_debug"))))
        w.core.flush()
        val p = w.profiles.all().single()
        assertEquals(MatchKey(MatchKeyKind.STABLE_ID, "match-77", Sentinels.NAME, ambiguous = false), p.matchKey)
        assertEquals("match-77", p.externalProfileId)
        // Without one the ID is made on the device and is not derived from the name.
        val plain = ReaderWorld()
        plain.core.onSnapshot(profileTop())
        plain.core.flush()
        assertEquals("screen:local-0001", plain.profiles.all().single().externalProfileId)
    }
}

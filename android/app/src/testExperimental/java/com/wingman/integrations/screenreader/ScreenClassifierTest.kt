package com.wingman.integrations.screenreader

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenClassifierTest {
    private val hinge = ScreenProfile.HINGE
    private fun kind(s: ScreenSnapshot, p: ScreenProfile = hinge) = ScreenClassifier.classify(s, p)

    @Test fun `a known prompt above an answer is a profile`() {
        assertEquals(ScreenClassification(ScreenKind.PROFILE, EvidenceSource.KNOWN_PROMPT), kind(profileTop()))
        assertEquals(ScreenClassification(ScreenKind.PROFILE, EvidenceSource.KNOWN_PROMPT), kind(profileScrolled()))
    }

    @Test fun `plain heading and body blocks are a profile by structure alone`() {
        val s = screen(
            ScreenReaderApps.HINGE, 1,
            promptCard("An invented heading", "An invented body, long enough.", 100),
            promptCard("Another invented heading", "Another invented body.", 500),
        )
        assertEquals(ScreenClassification(ScreenKind.PROFILE, EvidenceSource.STRUCTURE), kind(s))
        // One such block is not enough.
        val one = screen(ScreenReaderApps.HINGE, 1, promptCard("An invented heading", "An invented body.", 100))
        assertEquals(ScreenKind.OTHER, kind(one).kind)
    }

    @Test fun `a text input makes it a chat and tappable two-line rows make it the match list`() {
        assertEquals(ScreenClassification(ScreenKind.CHAT, EvidenceSource.STRUCTURE), kind(chatScreen()))
        assertEquals(ScreenClassification(ScreenKind.MATCH_LIST, EvidenceSource.STRUCTURE), kind(matchList()))
    }

    @Test fun `anything else is other`() {
        assertEquals(ScreenKind.OTHER, kind(opaqueScreen()).kind)
        assertEquals(ScreenKind.OTHER, kind(screen(ScreenReaderApps.HINGE, 1, label("Settings", 100), label("Notifications", 200), label("Privacy", 300))).kind)
    }

    @Test fun `view ids from the app definition win over structure`() {
        val withIds = hinge.copy(profileScreenIds = setOf("profile_scroll"), chatScreenIds = setOf("chat_recycler"), matchListIds = setOf("matches_list"))
        val chatLooking = chatScreen()
        val tagged = chatLooking.copy(root = chatLooking.root.copy(children = chatLooking.root.children + group(id = "co.hinge.app:id/profile_scroll_view")))
        assertEquals(ScreenClassification(ScreenKind.PROFILE, EvidenceSource.ID_HINT), kind(tagged, withIds))
        val list = screen(ScreenReaderApps.HINGE, 1, group(id = "co.hinge.app:id/matches_list"))
        assertEquals(ScreenClassification(ScreenKind.MATCH_LIST, EvidenceSource.ID_HINT), kind(list, withIds))
        // Without ids in the definition the same trees fall back to structure.
        assertEquals(ScreenKind.CHAT, kind(tagged).kind)
    }

    @Test fun `only hinge has a definition`() {
        assertEquals(hinge, ScreenProfile.forPackage("co.hinge.app"))
        assertEquals(null, ScreenProfile.forPackage("com.tinder"))
        assertEquals(null, ScreenProfile.forPackage("com.example.bank"))
    }
}

package com.wingman.features.conversation

import com.wingman.core.model.DeliveryState
import com.wingman.core.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preview / Compose-test fixtures must themselves be valid and honest. */
class UiFixturesTest {
    @Test fun inboxFixturesCoverEveryState() {
        assertEquals(UiState.Loading, UiFixtures.inboxLoading)
        assertEquals(UiState.Empty, UiFixtures.inboxEmpty)
        assertTrue((UiFixtures.inboxError as UiState.Error).error.retryable)
        val rows = (UiFixtures.inboxContent as UiState.Content).data.rows
        assertEquals(4, rows.size)
        // Sam and Riley are on the send-VERIFIED simulator; Jordan (UNVERIFIED) and Casey (UNSUPPORTED) are not.
        assertEquals(listOf("Sam", "Riley"), rows.filter { it.canSend }.map { it.contactName })
    }

    @Test fun conversationFixtures() {
        val states = (UiFixtures.conversationDeliveryStates as UiState.Content).data.rows
        assertEquals(DeliveryState.entries.size, states.size)
        assertEquals(listOf("Delivered"), states.mapNotNull { it.delivery }.filter { it.tone == LabelTone.SUCCESS }.map { it.text })
        assertEquals(2, states.count { it.delivery?.isFailure == true })

        val long = (UiFixtures.conversationLong as UiState.Content).data
        assertTrue(long.messageCount >= 100)
        assertEquals(long.messageCount, long.rows.map { it.key }.toSet().size)

        val unsupported = (UiFixtures.conversationUnsupported as UiState.Content).data.availability
        assertFalse(unsupported.canSend)
        assertEquals("Can't send from Wingman", unsupported.label)

        assertTrue((UiFixtures.draftReady as DraftPanel.Ready).suggestion!!.isNotBlank())
        assertFalse((UiFixtures.conversationError as UiState.Error).error.retryable)
    }
}

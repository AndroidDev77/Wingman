package com.wingman.e2e

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput

/*
 * WING-025: small helpers over the Compose test rule. Waits are condition-based
 * (`waitUntil` on the semantics tree), never fixed sleeps: network calls run on IO
 * dispatchers that Compose's idling does not track, so we wait for what the UI shows.
 */

typealias Rule = AndroidComposeTestRule<*, *>

const val UI_TIMEOUT_MS = 30_000L

fun Rule.exists(text: String, substring: Boolean = false): Boolean =
    onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

fun Rule.waitForText(text: String, substring: Boolean = false, timeoutMs: Long = UI_TIMEOUT_MS) {
    try {
        waitUntil(timeoutMs) { exists(text, substring) }
    } catch (e: Throwable) {
        throw AssertionError("UI never showed \"$text\" within ${timeoutMs}ms", e)
    }
}

/** A tab of a TabRow by its title (the same words can also appear on buttons). */
fun tab(title: String) = hasText(title) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/**
 * Taps [refresh] and waits up to [stepMs] for [text], at most [tries] times. For screens that
 * only re-read the server when asked (pull, not push).
 */
fun Rule.refreshUntil(text: String, refresh: String = "Refresh", tries: Int = 10, stepMs: Long = 3_000, substring: Boolean = false) {
    repeat(tries) {
        if (exists(text, substring)) return
        if (exists(refresh)) onAllNodesWithText(refresh)[0].performClick()
        runCatching { waitUntil(stepMs) { exists(text, substring) } }
    }
    waitForText(text, substring, stepMs)
}

/** Signs in through the debug-only "Developer sign-in" panel, unless already signed in. */
fun Rule.signInWithDevToken(token: String) {
    waitUntil(UI_TIMEOUT_MS) { exists("Dev token") || exists("Inbox") }
    if (!exists("Dev token")) return
    onNodeWithText("Dev token").performTextInput(token)
    onNodeWithText("Use dev token").performClick()
    waitForText("Inbox")
}

/**
 * Brings the first node with [text] on screen: a node already composed (e.g. in a
 * verticalScroll Column) is scrolled to directly; otherwise the first lazy list on screen is
 * scrolled until the item is composed.
 */
fun Rule.scrollTo(text: String, substring: Boolean = false) {
    if (exists(text, substring)) {
        onAllNodesWithText(text, substring = substring)[0].performScrollTo()
        return
    }
    runCatching { onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(text, substring = substring)) }
}

/** Waits for [text] in a (possibly lazy, possibly long) list, scrolling to it as needed. */
fun Rule.seeInList(text: String, substring: Boolean = false, timeoutMs: Long = UI_TIMEOUT_MS) {
    try {
        waitUntil(timeoutMs) {
            scrollTo(text, substring)
            exists(text, substring)
        }
    } catch (e: Throwable) {
        throw AssertionError("list never showed \"$text\" within ${timeoutMs}ms", e)
    }
}

/** The Switch in the same Row as the label [siblingText]. */
fun switchNear(siblingText: String) = isToggleable() and hasAnySibling(hasText(siblingText))

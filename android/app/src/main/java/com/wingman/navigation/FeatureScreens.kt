package com.wingman.navigation

import androidx.compose.runtime.Composable

/** What a feature hands the shell: a composable that receives its args and a [Navigator]. */
typealias ScreenContent = @Composable (args: ScreenArgs, navigator: Navigator) -> Unit

/**
 * THE feature registration point (integrator-owned, D-003). Feature tickets do NOT edit the nav
 * graph. Instead each feature exposes one public value from its own package, e.g.
 *
 * ```
 * // features/inbox/InboxEntry.kt (WING-014)
 * val InboxScreenEntry: ScreenContent = { _, navigator -> InboxScreen(onOpen = navigator::openConversation) }
 * ```
 *
 * and asks the integrator, in its handoff, to add one line below:
 *
 * ```
 * .register(Destination.Inbox, InboxScreenEntry)
 * ```
 *
 * Destinations without a registration render the shell's placeholder screen.
 */
fun featureScreens(): ScreenRegistry<ScreenContent> = ScreenRegistry<ScreenContent>()
    .register(Destination.Inbox, com.wingman.features.inbox.InboxScreenEntry) // WING-014
    .register(Destination.Conversation, com.wingman.features.conversation.ConversationScreenEntry) // WING-014
    .register(Destination.Settings, com.wingman.features.settings.SettingsScreenEntry) // WING-013
    .register(Destination.Onboarding, com.wingman.features.onboarding.OnboardingScreenEntry) // WING-013
// .register(Destination.Approvals, com.wingman.features.approvals.ApprovalsScreenEntry)      // WING-015
// .register(Destination.Discovery, com.wingman.features.discovery.DiscoveryScreenEntry)      // WING-019

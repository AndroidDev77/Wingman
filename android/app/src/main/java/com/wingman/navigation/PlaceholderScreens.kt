package com.wingman.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.di.AppContainer
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.ui.compose.LoadableViewModel
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.ui.theme.WingmanTheme

/*
 * Shell placeholder screens. They exist so navigation works before feature tickets land, and are
 * replaced (not edited) by registering a feature screen in FeatureScreens.kt.
 */

@Composable
fun PlaceholderScreen(destination: Destination, args: ScreenArgs, navigator: Navigator) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(destination.label, style = MaterialTheme.typography.headlineSmall)
        Text("Placeholder: this screen is provided by a feature ticket.", style = MaterialTheme.typography.bodyMedium)
        args.conversationId?.let { Text("Conversation $it", style = MaterialTheme.typography.labelSmall) }
        if (destination == Destination.Inbox) {
            // Lets the Conversation route be exercised before WING-014 lands.
            OutlinedButton(onClick = { navigator.openConversation(MockFixtures.CONVERSATION_SAM) }) {
                Text("Open sample conversation")
            }
        }
        if (!destination.topLevel) OutlinedButton(onClick = navigator::back) { Text("Back") }
    }
}

class DashboardViewModel(api: WingmanApi) : LoadableViewModel<DashboardSummary>(loader = { loadDashboardSummary(api) })

/** The one shell screen with live data: proves API wiring and the Loading/Error/Content states. */
@Composable
fun DashboardScreen(navigator: Navigator) {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel { DashboardViewModel(container.api) }
    val state by vm.state.collectAsStateWithLifecycle()
    UiStateContent(state, onRetry = vm::retry) { summary ->
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Hi ${summary.user.displayName ?: "there"}", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.padding(top = 4.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val pending = summary.pendingApprovals.toString() + if (summary.morePendingApprovals) "+" else ""
                    Text("Pending approvals: $pending", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (summary.globalAutomation == GlobalAutomationStatus.PAUSED) "Automation paused" else "Automation on per contact settings",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Backend ${summary.backendVersion}" + if (container.isMock) " (offline mock data)" else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            OutlinedButton(onClick = { navigator.navigateTo(Destination.Approvals) }) { Text("Review approvals") }
            // WING-027/028: drafts for Tinder/Bumble (assisted only) that the user sends by hand.
            val assisted = com.wingman.features.assisted.LocalAssistedBadge.current?.badge?.collectAsStateWithLifecycle()?.value
            OutlinedButton(onClick = { navigator.navigateTo(Destination.Assisted) }) {
                Text("Send by hand" + (assisted?.label?.let { " ($it)" } ?: ""))
            }
        }
    }
}

internal object PreviewNavigator : Navigator {
    override fun navigateTo(destination: Destination) = Unit
    override fun openConversation(conversationId: String) = Unit
    override fun back() = Unit
}

@Preview(name = "Placeholder")
@Composable
private fun PlaceholderPreview() = WingmanTheme {
    PlaceholderScreen(Destination.Inbox, ScreenArgs.NONE, PreviewNavigator)
}

@Preview(name = "Dashboard (mock API)")
@Composable
private fun DashboardPreview() = WingmanTheme {
    androidx.compose.runtime.CompositionLocalProvider(LocalAppContainer provides AppContainer.mock()) {
        DashboardScreen(PreviewNavigator)
    }
}

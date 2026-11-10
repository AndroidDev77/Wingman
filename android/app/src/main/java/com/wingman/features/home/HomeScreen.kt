package com.wingman.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.di.AppContainer
import com.wingman.core.model.GlobalAutomationStatus
import com.wingman.core.ui.compose.InfoBanner
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.SectionCard
import com.wingman.core.ui.compose.UiStateContent
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.features.assisted.LocalAssistedBadge
import com.wingman.navigation.DashboardSummary
import com.wingman.navigation.DashboardViewModel
import com.wingman.navigation.Destination
import com.wingman.navigation.Navigator
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.Spacing
import com.wingman.ui.theme.WingmanTheme

/*
 * Home dashboard. Replaces the shell's DashboardScreen placeholder once the integrator registers
 * it (navigation/FeatureScreens.kt is integrator-owned):
 *
 *     .register(Destination.Dashboard, com.wingman.features.home.HomeScreenEntry)
 *
 * It loads exactly what the placeholder loads (navigation/DashboardSummary.kt: health, me, global
 * automation state, first approvals page) through the same DashboardViewModel, and only adds
 * navigation shortcuts. Nothing here can approve or send anything.
 */

val HomeScreenEntry: ScreenContent = { _, navigator -> HomeScreen(navigator) }

@Composable
fun HomeScreen(navigator: Navigator) {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel { DashboardViewModel(container.api) }
    val state by vm.state.collectAsStateWithLifecycle()
    // WING-027/028: drafts for Tinder/Bumble (assisted only) that the user sends by hand.
    val assisted = LocalAssistedBadge.current?.badge?.collectAsStateWithLifecycle()?.value
    UiStateContent(state, onRetry = vm::retry) { summary ->
        HomeContent(
            summary = summary,
            sendByHandCount = assisted?.label,
            isMock = container.isMock,
            onNavigate = navigator::navigateTo,
        )
    }
}

/** Stateless dashboard so previews can drive it. */
@Composable
fun HomeContent(
    summary: DashboardSummary,
    sendByHandCount: String?,
    isMock: Boolean,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                "Hi ${summary.user.displayName ?: "there"}",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Here's what needs your attention.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isMock) InfoBanner("Offline mock data: built-in sample conversations. Nothing is sent.")

        ApprovalsCard(summary, onReview = { onNavigate(Destination.Approvals) })
        AutomationCard(summary.globalAutomation, onManage = { onNavigate(Destination.Approvals) })
        SendByHandCard(sendByHandCount, onOpen = { onNavigate(Destination.Assisted) })

        Text(
            "Shortcuts",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
        )
        Shortcut(Icons.Filled.Email, "Inbox", "Read conversations and ask for a suggested reply") { onNavigate(Destination.Inbox) }
        Shortcut(Icons.Filled.Favorite, "Discovery", "Review profiles you added") { onNavigate(Destination.Discovery) }
        Shortcut(Icons.Filled.Settings, "Settings", "Your goals, texting style, automation and connections") { onNavigate(Destination.Settings) }

        Text(
            "Wingman server ${summary.backendVersion}" + if (isMock) " (offline mock data)" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

@Composable
private fun ApprovalsCard(summary: DashboardSummary, onReview: () -> Unit) {
    val pending = summary.pendingApprovals.toString() + if (summary.morePendingApprovals) "+" else ""
    val waiting = summary.pendingApprovals > 0 || summary.morePendingApprovals
    SectionCard(
        containerColor = if (waiting) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (waiting) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Text("Drafts to approve", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(pending, style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            if (waiting) {
                "Pending approvals: $pending. Nothing is sent until you approve."
            } else {
                "You're all caught up. New drafts that need you will show up here."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (waiting) {
            Button(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) { Text("Review approvals") }
        } else {
            OutlinedButton(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) { Text("Review approvals") }
        }
    }
}

@Composable
private fun AutomationCard(status: GlobalAutomationStatus, onManage: () -> Unit) {
    val paused = status == GlobalAutomationStatus.PAUSED
    val tone = WingmanTheme.status
    SectionCard(
        containerColor = if (paused) tone.warningContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (paused) tone.onWarningContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(if (paused) Icons.Filled.Warning else Icons.Filled.Settings, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(if (paused) "Automation paused" else "Automation follows your settings", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (paused) {
                        "Wingman sends nothing automatically until you resume."
                    } else {
                        "Each person uses your automation mode or their own override. You can pause any time."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        OutlinedButton(onClick = onManage, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Manage in Approvals") }
    }
}

@Composable
private fun SendByHandCard(count: String?, onOpen: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("Send by hand", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Drafts for apps Wingman can't send on. You copy and send them yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text("Send by hand" + (count?.let { " ($it)" } ?: ""))
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/* ------------------------------------------------------------------ previews */

private fun previewSummary(pending: Int, paused: Boolean) = DashboardSummary(
    user = MockFixtures.me,
    backendVersion = "0.1.0",
    globalAutomation = if (paused) GlobalAutomationStatus.PAUSED else GlobalAutomationStatus.ACTIVE,
    pendingApprovals = pending,
    morePendingApprovals = false,
)

@Preview(name = "Home: drafts waiting", heightDp = 900)
@Composable
private fun HomePreview() = WingmanTheme {
    Surface { HomeContent(previewSummary(pending = 2, paused = false), sendByHandCount = "1", isMock = true, onNavigate = {}) }
}

@Preview(name = "Home: caught up, paused (dark)", heightDp = 900)
@Composable
private fun HomeDarkPreview() = WingmanTheme(darkTheme = true) {
    Surface { HomeContent(previewSummary(pending = 0, paused = true), sendByHandCount = null, isMock = false, onNavigate = {}) }
}

@Preview(name = "Home (mock API)", heightDp = 900)
@Composable
private fun HomeMockApiPreview() = WingmanTheme {
    CompositionLocalProvider(LocalAppContainer provides AppContainer.mock()) {
        Surface { HomeScreen(com.wingman.navigation.PreviewNavigator) }
    }
}

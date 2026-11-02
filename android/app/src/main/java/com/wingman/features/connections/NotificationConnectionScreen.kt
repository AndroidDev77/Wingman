package com.wingman.features.connections

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.model.Verification
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.integrations.notifications.android.NotificationAccessActivity
import com.wingman.integrations.notifications.android.NotificationConnectorRuntime
import com.wingman.integrations.notifications.android.NotificationSyncRuntime
import kotlinx.coroutines.Dispatchers

/*
 * I-033 / WING-027/028 Compose layer: per-app notification toggles on the Settings screen. Compiled by
 * CI's Android job; never run on a device (I-003). All rules live in NotificationConnectionState.kt.
 */

class NotificationConnectionsViewModel(app: Application) : ViewModel() {
    val holder = NotificationConnectionsState(
        scope = viewModelScope,
        host = NotificationSyncRuntime.host(app),
        accessGranted = { NotificationConnectorRuntime.isAccessGranted(app) },
        io = Dispatchers.IO,
        afterChange = { NotificationSyncRuntime.requestSync(app) },
    )
}

/**
 * Settings section, collapsed by default to one row so the Settings page keeps its length (the SMS
 * section above it and the E2E scroll flow are unchanged). Expanded: notification access, then one
 * card per dating app (Tinder, Bumble). The ViewModel is created only when expanded.
 */
@Composable
fun NotificationConnectionSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(NotificationConnectionCopy.COLLAPSED_SUMMARY, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Manage") }
    }
    if (expanded) NotificationConnectionDetails()
}

@Composable
private fun NotificationConnectionDetails() {
    val app = LocalContext.current.applicationContext as Application
    val vm = wingmanViewModel(key = "notification-connections") { NotificationConnectionsViewModel(app) }
    val ui by vm.holder.state.collectAsStateWithLifecycle()
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.holder.refresh() }
    LaunchedEffect(Unit) { vm.holder.refresh() }
    NotificationConnectionContent(
        ui = ui,
        onOpenAccess = { accessLauncher.launch(Intent(app, NotificationAccessActivity::class.java)) },
        onReading = vm.holder::setReading,
        onDirectReply = vm.holder::setDirectReply,
    )
}

@Composable
fun NotificationConnectionContent(
    ui: NotificationConnectionsUi,
    onOpenAccess: () -> Unit,
    onReading: (String, Boolean) -> Unit,
    onDirectReply: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ui.accessLabel, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenAccess) { Text(if (ui.accessGranted) "Notification access settings" else "Turn on notification access") }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ui.platforms.forEach { p -> PlatformCard(p, ui.busy == p.platform, onReading, onDirectReply) }
    }
}

@Composable
private fun PlatformCard(p: NotificationPlatformView, busy: Boolean, onReading: (String, Boolean) -> Unit, onDirectReply: (String, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
            }
            Text(p.assistedLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Status: ${p.statusLabel}", style = MaterialTheme.typography.bodySmall)
            p.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Read notifications", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = p.readingOn, enabled = p.readingToggleEnabled, onCheckedChange = { onReading(p.platform, it) })
            }
            Text(NotificationConnectionCopy.READING_EXPLANATION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.readingHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Direct reply", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = p.directReplyOn, enabled = p.directReplyToggleEnabled, onCheckedChange = { onDirectReply(p.platform, it) })
            }
            Text(p.directReplyExplanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.capabilities.forEach { row ->
                Text(
                    "${row.name}: ${row.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.status == Verification.VERIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

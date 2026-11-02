package com.wingman.features.assisted

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.api.WingmanApi
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.integrations.notifications.NotificationInspector
import com.wingman.integrations.notifications.android.NotificationConnectorRuntime
import com.wingman.navigation.ScreenContent

/*
 * WING-027/028 Compose layer of the assisted flow. Compiled by CI's Android job; never run on a
 * device (I-003). All rules live in AssistedState.kt (JVM-tested).
 */

/** Registered for `Destination.Assisted` in navigation/FeatureScreens.kt. */
val AssistedScreenEntry: ScreenContent = { _, navigator -> AssistedScreen(onBack = navigator::back) }

/** Opens a dating app with its official launch intent. Never taps, types or uses accessibility. */
object AssistedAppOpener {
    fun packageFor(platform: String): String? =
        NotificationConnectorRuntime.registry().forPlatform(platform)?.packageNames?.firstOrNull()
            ?: NotificationInspector.CANDIDATE_PACKAGES.entries.firstOrNull { it.value == platform }?.key

    fun open(context: Context, platform: String): OpenAppResult {
        if (platform == "imessage") return OpenAppResult.Unavailable("Open Messages on your Mac to send this.")
        val pkg = packageFor(platform) ?: return OpenAppResult.Unavailable("Wingman doesn't know how to open this app.")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return OpenAppResult.Unavailable("${AssistedCopy.platformLabel(platform)} isn't installed on this phone.")
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OpenAppResult.Opened
        }.getOrElse { OpenAppResult.Unavailable("Couldn't open ${AssistedCopy.platformLabel(platform)}.") }
    }
}

class AssistedViewModel(api: WingmanApi, context: Context, badge: AssistedBadgeState?) : ViewModel() {
    private val app = context.applicationContext
    val holder = AssistedState(
        scope = viewModelScope,
        api = api,
        clipboard = { text ->
            app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Wingman draft", text))
        },
        openApp = { platform, _ -> AssistedAppOpener.open(app, platform) },
        onPendingCount = { count, more -> badge?.report(count, more) },
    )

    init {
        holder.load()
    }
}

/** The shell's badge state (provided by MainShell), so the screen reports a fresher count after each change. */
val LocalAssistedBadge = staticCompositionLocalOf<AssistedBadgeState?> { null }

@Composable
fun AssistedScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val badge = LocalAssistedBadge.current
    val vm = wingmanViewModel(key = "assisted") { AssistedViewModel(container.api, context, badge) }
    val ui by vm.holder.state.collectAsStateWithLifecycle()
    AssistedContent(
        ui = ui,
        onRefresh = vm.holder::load,
        onCopy = vm.holder::copy,
        onOpen = vm.holder::open,
        onConfirm = { vm.holder.confirmSent(it) },
        onDismiss = vm.holder::dismiss,
        onBack = onBack,
    )
}

@Composable
fun AssistedContent(
    ui: AssistedUi,
    onRefresh: () -> Unit,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AssistedCopy.TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                    TextButton(onClick = onBack) { Text("Back") }
                }
                Text(AssistedCopy.EXPLANATION, style = MaterialTheme.typography.bodySmall)
                ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (ui.loading) CircularProgressIndicator(Modifier.padding(8.dp))
                if (!ui.loading && ui.rows.isEmpty() && ui.error == null) {
                    Text("No drafts waiting for you to send.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(ui.rows, key = { it.id }) { row -> AssistedCard(row, onCopy, onOpen, onConfirm, onDismiss) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(AssistedCopy.NOTICES_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(AssistedCopy.NOTICES_EXPLANATION, style = MaterialTheme.typography.bodySmall)
                if (ui.notices.isEmpty()) Text("No notices.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(ui.notices, key = { "n-" + it.id }) { n -> NoticeCard(n) }
    }
}

@Composable
private fun AssistedCard(
    row: AssistedRow,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${row.platformLabel} · ${row.statusLabel}", style = MaterialTheme.typography.labelLarge)
            Text(row.text, style = MaterialTheme.typography.bodyMedium)
            row.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.actionable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onCopy(row.id) }) { Text("Copy") }
                    OutlinedButton(onClick = { onOpen(row.id) }) { Text("Open app") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onConfirm(row.id) }, enabled = !row.busy) { Text("I sent it") }
                    TextButton(onClick = { onDismiss(row.id) }, enabled = !row.busy) { Text("Dismiss") }
                    if (row.busy) CircularProgressIndicator(Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(n: NoticeRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${n.platformLabel} · ${n.kindLabel}", style = MaterialTheme.typography.labelLarge)
            n.senderLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium)
            n.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

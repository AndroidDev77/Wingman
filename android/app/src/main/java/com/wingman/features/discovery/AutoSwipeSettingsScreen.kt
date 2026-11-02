package com.wingman.features.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.api.WingmanApi
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.wingmanViewModel

/*
 * WING-031 Compose layer of Settings > Auto-swipe. Compiled by CI; never run on a device (I-003).
 * Rules in AutoSwipeState.kt (JVM-tested).
 */

class AutoSwipeViewModel(api: WingmanApi) : ViewModel() {
    val holder = AutoSwipeState(viewModelScope, api)

    init {
        holder.load()
    }
}

/**
 * Shown in Settings, collapsed by default to one row (as the notification section, PR #50): the
 * Settings page keeps its length and nothing is fetched until the user opens it. Expanded: one
 * toggle per dating-app connection, disabled with the reason unless swipe is VERIFIED.
 */
@Composable
fun AutoSwipeSettingsSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(AutoSwipeCopy.COLLAPSED_SUMMARY, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Details") }
    }
    if (expanded) AutoSwipeDetails()
}

@Composable
private fun AutoSwipeDetails() {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel(key = "auto-swipe") { AutoSwipeViewModel(container.api) }
    val ui by vm.holder.state.collectAsStateWithLifecycle()
    AutoSwipeContent(ui, onToggle = vm.holder::toggle)
}

@Composable
fun AutoSwipeContent(ui: AutoSwipeUi, onToggle: (String, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(AutoSwipeCopy.INTRO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.loading) CircularProgressIndicator()
        ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!ui.loading && ui.error == null && ui.rows.isEmpty()) Text(ui.emptyExplanation, style = MaterialTheme.typography.bodySmall)
        ui.rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${row.platformLabel} auto-swipe", style = MaterialTheme.typography.bodyMedium)
                    Text(row.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = row.checked, enabled = row.toggleEnabled, onCheckedChange = { onToggle(row.connectionId, it) })
            }
        }
    }
}

package com.wingman.features.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.BuildConfig
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ProfileDecision
import com.wingman.core.model.ReasonEffect
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.navigation.ScreenContent

/*
 * WING-031 Compose layer of the Discovery review workflow. Compiled by CI's Android job; never run
 * on a device (I-003). All rules and labels live in DiscoveryState.kt / DiscoveryImport.kt
 * (JVM-tested).
 */

/** Registered for `Destination.Discovery` in navigation/FeatureScreens.kt. */
val DiscoveryScreenEntry: ScreenContent = { _, _ -> DiscoveryScreen() }

class DiscoveryViewModel(api: WingmanApi) : ViewModel() {
    val keys = IdempotencyKeyRegistry()
    val holder = DiscoveryState(viewModelScope, api, keys)

    init {
        holder.load()
    }
}

@Composable
fun DiscoveryScreen() {
    val container = LocalAppContainer.current
    val vm = wingmanViewModel(key = "discovery") { DiscoveryViewModel(container.api) }
    val ui by vm.holder.state.collectAsStateWithLifecycle()
    var importOpen by rememberSaveable { mutableStateOf(false) }
    DiscoveryContent(
        ui = ui,
        importOpen = importOpen,
        onToggleImport = { importOpen = !importOpen },
        onImport = { form, attempt -> importForm(vm, form, attempt) { ok -> if (ok) importOpen = false } },
        onFixtures = if (BuildConfig.DEBUG) vm.holder::importFixtures else null,
        onShowExcluded = vm.holder::setShowExcluded,
        onRefresh = vm.holder::load,
        onDecide = vm.holder::decide,
        onExecute = vm.holder::execute,
    )
}

/** Returns the parse errors to show, or null when the request was sent. */
private fun importForm(vm: DiscoveryViewModel, form: ManualImportForm, attempt: ImportAttempt, onDone: (Boolean) -> Unit): List<String>? =
    when (val parsed = ManualImport.parse(attempt, form)) {
        is ImportParse.Invalid -> parsed.errors
        is ImportParse.Ok -> {
            vm.holder.import(parsed.request, attempt.key, onDone)
            null
        }
    }

@Composable
fun DiscoveryContent(
    ui: DiscoveryUi,
    importOpen: Boolean,
    onToggleImport: () -> Unit,
    onImport: (ManualImportForm, ImportAttempt) -> List<String>?,
    onFixtures: (() -> Unit)?,
    onShowExcluded: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDecide: (String, ProfileDecision) -> Unit,
    onExecute: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DiscoveryCopy.TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                }
                Text(DiscoveryCopy.EXPLANATION, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onToggleImport) { Text(if (importOpen) "Close" else "Add a profile") }
                    onFixtures?.let { TextButton(onClick = it, enabled = !ui.importing) { Text("Add demo profiles (debug)") } }
                }
                if (importOpen) ManualImportSection(ui.importing, onImport)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = ui.showExcluded, onCheckedChange = onShowExcluded)
                    Text(DiscoveryCopy.SHOW_EXCLUDED, style = MaterialTheme.typography.bodyMedium)
                }
                ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (ui.loading) CircularProgressIndicator(Modifier.padding(8.dp))
                if (!ui.loading && ui.cards.isEmpty() && ui.error == null) Text(DiscoveryCopy.EMPTY, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(ui.cards, key = { it.id }) { card -> ProfileCardView(card, onDecide, onExecute) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCardView(card: ProfileCard, onDecide: (String, ProfileDecision) -> Unit, onExecute: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${card.name} · ${card.platformLabel}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(card.scoreLabel, style = MaterialTheme.typography.labelLarge)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.confidenceLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                if (card.stale) Text(DiscoveryCopy.STALE_BADGE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                if (card.excluded) Text(DiscoveryCopy.EXCLUDED_BADGE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            if (card.stale) Text(DiscoveryCopy.STALE_NOTE, style = MaterialTheme.typography.bodySmall)
            if (card.excluded) Text("Dealbreaker: " + card.dealbreakers.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            card.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            card.prompts.forEach { (q, a) -> Text("$q: $a", style = MaterialTheme.typography.bodySmall) }
            if (card.attributes.isNotEmpty()) {
                Text(card.attributes.joinToString(" · ") { (k, v) -> "$k: $v" }, style = MaterialTheme.typography.bodySmall)
            }
            if (card.reasons.isNotEmpty()) {
                HorizontalDivider()
                Text("Why this score", style = MaterialTheme.typography.labelLarge)
                card.reasons.forEach { r ->
                    val sign = when (r.effect) {
                        ReasonEffect.POSITIVE -> "+"
                        ReasonEffect.NEGATIVE -> "−"
                        ReasonEffect.NEUTRAL -> "·"
                    }
                    val cites = if (r.cites.isEmpty()) "" else " (from: ${r.cites.joinToString(", ")})"
                    Text("$sign ${r.text}$cites", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (card.unknowns.isNotEmpty()) {
                Text("Not stated: " + card.unknowns.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            card.statusLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onDecide(card.id, ProfileDecision.LIKE) }, enabled = !card.busy) { Text("Like") }
                OutlinedButton(onClick = { onDecide(card.id, ProfileDecision.PASS) }, enabled = !card.busy) { Text("Pass") }
                TextButton(onClick = { onDecide(card.id, ProfileDecision.SKIP) }, enabled = !card.busy) { Text("Skip") }
                if (card.busy) CircularProgressIndicator(Modifier.padding(4.dp))
            }
            HorizontalDivider()
            // The separate platform control: disabled, with the reason, unless the server's state allows it.
            OutlinedButton(onClick = { onExecute(card.id) }, enabled = card.execute.enabled && !card.busy) { Text(card.execute.label) }
            Text(card.execute.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualImportSection(importing: Boolean, onImport: (ManualImportForm, ImportAttempt) -> List<String>?) {
    var form by remember { mutableStateOf(ManualImportForm()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    // One attempt (key + time seen) per form content: a retry of the same submit reuses it.
    var attempt by remember { mutableStateOf(ImportAttempt.fresh()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add a profile you saw", style = MaterialTheme.typography.titleSmall)
            Text(
                "Type only what the app showed. Wingman doesn't read photos or guess anything you leave out.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualImport.PLATFORMS.forEach { p ->
                    FilterChip(
                        selected = form.provider == p,
                        onClick = { form = form.copy(provider = p); attempt = ImportAttempt.fresh() },
                        label = { Text(DiscoveryCopy.platformLabel(p)) },
                    )
                }
            }
            OutlinedTextField(value = form.displayName, onValueChange = { form = form.copy(displayName = it); attempt = ImportAttempt.fresh() }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.bio, onValueChange = { form = form.copy(bio = it); attempt = ImportAttempt.fresh() }, label = { Text("Bio") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.prompts, onValueChange = { form = form.copy(prompts = it); attempt = ImportAttempt.fresh() }, label = { Text("Prompts (one per line: Prompt | Answer)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = form.attributes, onValueChange = { form = form.copy(attributes = it); attempt = ImportAttempt.fresh() }, label = { Text("Details (one per line: name: value)") }, modifier = Modifier.fillMaxWidth())
            errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { errors = onImport(form, attempt) ?: emptyList() }, enabled = !importing) { Text("Add to queue") }
        }
    }
}

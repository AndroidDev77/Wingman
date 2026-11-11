package com.wingman.features.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.BuildConfig
import com.wingman.core.api.IdempotencyKeyRegistry
import com.wingman.core.api.WingmanApi
import com.wingman.core.model.ProfileDecision
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ReasonEffect
import com.wingman.core.ui.compose.BannerTone
import com.wingman.core.ui.compose.ContactAvatar
import com.wingman.core.ui.compose.EmptyState
import com.wingman.core.ui.compose.InfoBanner
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.core.ui.compose.ScreenHeader
import com.wingman.core.ui.compose.SectionCard
import com.wingman.core.ui.compose.StatusPill
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.navigation.ScreenContent
import com.wingman.ui.theme.Spacing
import com.wingman.ui.theme.WingmanTheme

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

@OptIn(ExperimentalLayoutApi::class)
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "header") {
            ScreenHeader(DiscoveryCopy.TITLE, subtitle = DiscoveryCopy.EXPLANATION) {
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Refresh")
                }
            }
        }
        item(key = "controls") {
            Column(Modifier.padding(horizontal = Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FilledTonalButton(onClick = onToggleImport) {
                        Icon(if (importOpen) Icons.Filled.Close else Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(if (importOpen) "Close" else "Add a profile")
                    }
                    onFixtures?.let { TextButton(onClick = it, enabled = !ui.importing) { Text("Add demo profiles (debug)") } }
                }
                if (importOpen) ManualImportSection(ui.importing, onImport)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = ui.showExcluded, role = Role.Switch, onValueChange = onShowExcluded)
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(DiscoveryCopy.SHOW_EXCLUDED, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = ui.showExcluded, onCheckedChange = null)
                }
                ui.message?.let { InfoBanner(it) }
                ui.error?.let { InfoBanner(it, tone = BannerTone.ERROR) }
                if (ui.loading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(Spacing.sm)) }
                }
                if (!ui.loading && ui.cards.isEmpty() && ui.error == null) EmptyState(DiscoveryCopy.EMPTY, icon = Icons.Filled.Favorite)
            }
        }
        items(ui.cards, key = { it.id }) { card ->
            Box(Modifier.padding(horizontal = Spacing.screen)) { ProfileCardView(card, onDecide, onExecute) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCardView(card: ProfileCard, onDecide: (String, ProfileDecision) -> Unit, onExecute: (String) -> Unit) {
    val status = WingmanTheme.status
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ContactAvatar(card.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(card.platformLabel)
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(card.scoreLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            card.confidenceLabel?.let { StatusPill(it) }
            if (card.stale) StatusPill(DiscoveryCopy.STALE_BADGE, containerColor = status.warningContainer, contentColor = status.onWarningContainer)
            if (card.excluded) {
                StatusPill(
                    DiscoveryCopy.EXCLUDED_BADGE,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (card.stale) Text(DiscoveryCopy.STALE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (card.excluded) Text("Dealbreaker: " + card.dealbreakers.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        card.bio?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        card.prompts.forEach { (q, a) ->
            Column {
                Text(q, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(a, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (card.attributes.isNotEmpty()) {
            Text(
                card.attributes.joinToString(" · ") { (k, v) -> "$k: $v" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (card.reasons.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Why this score", style = MaterialTheme.typography.labelLarge)
            card.reasons.forEach { r ->
                val (sign, color) = when (r.effect) {
                    ReasonEffect.POSITIVE -> "+" to status.success
                    ReasonEffect.NEGATIVE -> "−" to MaterialTheme.colorScheme.error
                    ReasonEffect.NEUTRAL -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                val cites = if (r.cites.isEmpty()) "" else " (from: ${r.cites.joinToString(", ")})"
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(sign, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Bold)
                    Text("${r.text}$cites", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (card.unknowns.isNotEmpty()) {
            Text("Not stated: " + card.unknowns.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        card.statusLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onDecide(card.id, ProfileDecision.LIKE) }, enabled = !card.busy, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("Like")
            }
            OutlinedButton(onClick = { onDecide(card.id, ProfileDecision.PASS) }, enabled = !card.busy, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Text("Pass")
            }
            TextButton(onClick = { onDecide(card.id, ProfileDecision.SKIP) }, enabled = !card.busy, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Text("Skip")
            }
            if (card.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // The separate platform control: disabled, with the reason, unless the server's state allows it.
        OutlinedButton(onClick = { onExecute(card.id) }, enabled = card.execute.enabled && !card.busy) { Text(card.execute.label) }
        Text(card.execute.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/* ------------------------------------------------------------------ previews */

private val previewCards = listOf(
    ProfileCard(
        id = "pr1", provider = ProfileProvider.HINGE, platformLabel = "Hinge", name = "Riley",
        bio = "Climber, bad at karaoke, good at finding taco trucks.",
        prompts = listOf("A perfect Sunday" to "Farmers market, then a long hike."),
        attributes = listOf("Looking for" to "Long-term"),
        scoreLabel = "Fit 82/100", confidenceLabel = "Medium confidence",
        reasons = listOf(
            ReasonRow("Also wants something long-term", ReasonEffect.POSITIVE, listOf("Looking for")),
            ReasonRow("Different weekend rhythm", ReasonEffect.NEGATIVE, emptyList()),
        ),
        unknowns = listOf("Kids"), stale = false, excluded = false, dealbreakers = emptyList(),
        decision = null, statusLabel = null, busy = false,
        execute = ExecuteControl("Like on Hinge", enabled = false, explanation = "Not executable on Hinge: Wingman can't swipe there."),
    ),
    ProfileCard(
        id = "pr2", provider = ProfileProvider.TINDER, platformLabel = "Tinder", name = "Casey",
        bio = null, prompts = emptyList(), attributes = emptyList(),
        scoreLabel = "Not scored", confidenceLabel = null, reasons = emptyList(), unknowns = emptyList(),
        stale = true, excluded = true, dealbreakers = listOf("Smokes"),
        decision = ProfileDecision.PASS, statusLabel = "Decision recorded (Pass)", busy = false,
        execute = ExecuteControl("Pass on Tinder", enabled = false, explanation = "Not executable on Tinder."),
    ),
)

@Composable
private fun DiscoveryPreviewFrame(ui: DiscoveryUi, dark: Boolean) = WingmanTheme(darkTheme = dark) {
    Surface {
        DiscoveryContent(ui, importOpen = false, onToggleImport = {}, onImport = { _, _ -> null }, onFixtures = {}, onShowExcluded = {}, onRefresh = {}, onDecide = { _, _ -> }, onExecute = {})
    }
}

@Preview(name = "Discovery", heightDp = 1200)
@Composable
private fun DiscoveryPreview() = DiscoveryPreviewFrame(DiscoveryUi(loading = false, cards = previewCards, showExcluded = true), dark = false)

@Preview(name = "Discovery dark", heightDp = 1200)
@Composable
private fun DiscoveryDarkPreview() = DiscoveryPreviewFrame(DiscoveryUi(loading = false, cards = previewCards, showExcluded = true), dark = true)

@Preview(name = "Discovery empty")
@Composable
private fun DiscoveryEmptyPreview() = DiscoveryPreviewFrame(DiscoveryUi(loading = false), dark = false)

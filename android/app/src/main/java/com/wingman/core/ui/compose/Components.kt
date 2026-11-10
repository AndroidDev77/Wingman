package com.wingman.core.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wingman.ui.theme.Spacing
import com.wingman.ui.theme.WingmanTheme

/*
 * Small, stateless building blocks shared by the feature screens so headers, cards, pills and
 * banners look the same everywhere. They only render; no screen logic lives here.
 */

/** Large title for a top-level tab, with an optional subtitle and trailing actions (e.g. Refresh). */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.sm, top = Spacing.md, bottom = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            actions()
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.sm),
            )
        }
    }
}

/** Section title inside a scrolling screen; marked as a heading for screen readers. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = Spacing.sm).semantics { heading() },
    )
}

/** A full-width card with the standard inner padding and spacing. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = content)
    }
}

/** A small rounded label, e.g. a platform name or a status. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
) {
    Surface(modifier, shape = MaterialTheme.shapes.extraSmall, color = containerColor, contentColor = contentColor) {
        Row(
            Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Circle with a person's initial. Decorative: the name is always shown next to it. */
@Composable
fun ContactAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val (bg, fg) = palette[Math.floorMod(name.hashCode(), palette.size)]
    Box(
        modifier.size(size).background(bg, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialOf(name),
            color = fg,
            style = if (size >= 40.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** First letter or digit of [name], upper-cased; "?" when there is none. */
fun initialOf(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

enum class BannerTone { INFO, WARNING, ERROR }

/** Inline banner for notices that belong to a screen (not transient snackbars). */
@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, tone: BannerTone = BannerTone.INFO) {
    val status = WingmanTheme.status
    val (bg, fg) = when (tone) {
        BannerTone.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BannerTone.WARNING -> status.warningContainer to status.onWarningContainer
        BannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = bg, contentColor = fg) {
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (tone == BannerTone.INFO) Icons.Filled.Info else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Centered icon + title + message (+ optional action) for empty lists. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = Icons.Filled.Info,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/* ------------------------------------------------------------------ previews */

@Composable
private fun ComponentsSample() {
    Surface {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ScreenHeader("Inbox", subtitle = "Every conversation Wingman can see.")
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ContactAvatar("Sam")
                    Text("Sam", style = MaterialTheme.typography.titleMedium)
                    StatusPill("SMS")
                }
            }
            InfoBanner("Showing built-in demo data.")
            InfoBanner("Send replies: UNVERIFIED", tone = BannerTone.WARNING)
            InfoBanner("Couldn't refresh.", tone = BannerTone.ERROR)
            EmptyState("No drafts are waiting for your approval.", title = "All caught up")
        }
    }
}

@Preview(name = "Components")
@Composable
private fun ComponentsPreview() = WingmanTheme(darkTheme = false) { ComponentsSample() }

@Preview(name = "Components dark")
@Composable
private fun ComponentsDarkPreview() = WingmanTheme(darkTheme = true) { ComponentsSample() }

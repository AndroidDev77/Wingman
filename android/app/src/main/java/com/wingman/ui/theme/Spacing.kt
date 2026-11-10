package com.wingman.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing tokens (4 dp grid). Screens use [screen] as their side padding, [lg] inside cards and
 * [sm]/[md] between related items, so every screen lines up the same way.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Horizontal padding of every screen. */
    val screen = 16.dp

    /** Minimum touch target (Material accessibility guidance). */
    val touchTarget = 48.dp
}

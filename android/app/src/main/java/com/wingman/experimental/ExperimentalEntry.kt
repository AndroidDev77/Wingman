package com.wingman.experimental

import android.content.Context

/*
 * D-023 / D-026: the experimental unofficial-API connectors (today only WING-029's Hinge connector)
 * are compiled ONLY into the experimental build. `android/app/build.gradle.kts` adds exactly one of
 * two source sets to `main`:
 *
 *  - `src/standard/java` (every normal build, CI, the beta and any future Play build): a no-op
 *    `ExperimentalModules` that references no experimental class;
 *  - `src/experimental/java` (only with `-Pwingman.experimental=true`): the connector, its screen
 *    and an `ExperimentalModules` that wires them.
 *
 * Both define `com.wingman.experimental.ExperimentalModules` with the same members:
 *
 *     const val INCLUDED: Boolean
 *     fun init(context: Context, container: com.wingman.core.di.AppContainer)
 *     fun settingsEntries(): List<ExperimentalEntry>
 *
 * so `MainActivity` and Settings compile against either. This file is the only shared type.
 */

/** A row an experimental module adds to Settings. The standard build never has any. */
data class ExperimentalEntry(
    val section: String,
    val description: String,
    val actionLabel: String,
    val open: (Context) -> Unit,
)

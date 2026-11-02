package com.wingman.experimental

import android.content.Context
import com.wingman.core.di.AppContainer

/**
 * STANDARD build (D-023, D-026): no experimental connector is compiled in. Every member is a no-op,
 * and this file references no class from `com.wingman.integrations.hinge`.
 * The Hinge notification plugin (the fallback) is registered by `PlatformPlugins` in every build.
 */
object ExperimentalModules {
    /** False: this build contains no unofficial-API code. */
    const val INCLUDED: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context, container: AppContainer) = Unit

    fun settingsEntries(): List<ExperimentalEntry> = emptyList()
}

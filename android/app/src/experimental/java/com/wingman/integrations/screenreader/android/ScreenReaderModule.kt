package com.wingman.integrations.screenreader.android

import android.content.Context
import android.content.Intent
import com.wingman.core.di.AppContainer
import com.wingman.experimental.ExperimentalEntry

/*
 * THIN ANDROID ADAPTER (D-032, experimental build only). Compiled in CI; never run on a device.
 */

/**
 * Entry point of the read-only screen reader, wired by ExperimentalModules. It needs none of the
 * `wingman.hinge.*` build properties (those belong to the unofficial-API connector): the flag
 * `-Pwingman.experimental=true` alone is enough.
 */
object ScreenReaderModule {
    fun init(context: Context, container: AppContainer) = ScreenReaderRuntime.init(context.applicationContext, container.api)

    fun settingsEntry(): ExperimentalEntry = ExperimentalEntry(
        section = "Screen reader (experimental)",
        description = "Reads a match's profile text from the Hinge screen so drafts can refer to it. Read-only; off until you turn it on.",
        actionLabel = "Open",
        open = { ctx -> ctx.startActivity(Intent(ctx, ScreenReaderActivity::class.java)) },
    )
}

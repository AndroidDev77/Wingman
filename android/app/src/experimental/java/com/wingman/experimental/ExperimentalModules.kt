package com.wingman.experimental

import android.content.Context
import android.content.Intent
import com.wingman.core.di.AppContainer
import com.wingman.integrations.hinge.android.HingeExperimentalActivity
import com.wingman.integrations.hinge.android.HingeModule
import com.wingman.integrations.screenreader.android.ScreenReaderModule

/**
 * EXPERIMENTAL build only (`-Pwingman.experimental=true`, D-021, D-023, D-026, D-032). Wires:
 *  - WING-029's unofficial-API Hinge connector: still inert unless the client identity is
 *    configured, and opt-in, approval-only and swipe-free when it is (see HingeModule);
 *  - D-032's read-only screen reader: needs no client identity, and reads nothing until the user
 *    switches it on in Wingman and in Android's Accessibility settings (see ScreenReaderModule).
 */
object ExperimentalModules {
    /** True: this build contains the experimental code. Never a Play build. */
    const val INCLUDED: Boolean = true

    fun init(context: Context, container: AppContainer) {
        HingeModule.init(context, container)
        ScreenReaderModule.init(context, container)
    }

    fun settingsEntries(): List<ExperimentalEntry> {
        val entries = ArrayList<ExperimentalEntry>()
        entries += ScreenReaderModule.settingsEntry()
        if (HingeModule.connector() != null) {
            entries += ExperimentalEntry(
                section = "Hinge (experimental)",
                description = "Unofficial interface; your Hinge account can be banned. Approval-only.",
                actionLabel = "Open",
                open = { ctx -> ctx.startActivity(Intent(ctx, HingeExperimentalActivity::class.java)) },
            )
        }
        return entries
    }
}

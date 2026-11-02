package com.wingman.experimental

import android.content.Context
import android.content.Intent
import com.wingman.core.di.AppContainer
import com.wingman.integrations.hinge.android.HingeExperimentalActivity
import com.wingman.integrations.hinge.android.HingeModule

/**
 * EXPERIMENTAL build only (`-Pwingman.experimental=true`, D-021, D-023, D-026): wires WING-029's
 * unofficial-API Hinge connector. Still inert unless the client identity is configured, and
 * opt-in, approval-only and swipe-free when it is (see HingeModule).
 */
object ExperimentalModules {
    /** True: this build contains the experimental connector code. Never a Play build. */
    const val INCLUDED: Boolean = true

    fun init(context: Context, container: AppContainer) = HingeModule.init(context, container)

    fun settingsEntries(): List<ExperimentalEntry> =
        if (HingeModule.connector() == null) {
            emptyList()
        } else {
            listOf(
                ExperimentalEntry(
                    section = "Hinge (experimental)",
                    description = "Unofficial interface; your Hinge account can be banned. Approval-only.",
                    actionLabel = "Open",
                    open = { ctx -> ctx.startActivity(Intent(ctx, HingeExperimentalActivity::class.java)) },
                ),
            )
        }
}

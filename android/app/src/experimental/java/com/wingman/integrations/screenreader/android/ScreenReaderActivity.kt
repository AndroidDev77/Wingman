package com.wingman.integrations.screenreader.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wingman.integrations.screenreader.CapturedProfile
import com.wingman.integrations.screenreader.DeleteOutcome
import com.wingman.integrations.screenreader.LinkCandidate
import com.wingman.integrations.screenreader.LinkDecision
import com.wingman.integrations.screenreader.ScreenReaderApps
import com.wingman.integrations.screenreader.ScreenReaderExplainer
import com.wingman.integrations.screenreader.UploadState
import java.text.DateFormat
import java.util.Date

/*
 * THIN ANDROID ADAPTER (D-032, experimental build only). Compiled in CI; never run on a device.
 *
 * The screen reader's one screen, reached from Settings > "Screen reader (experimental)":
 *  1. the plain-language explainer and its acknowledgement (nothing can be switched on before it);
 *  2. the state of the Android accessibility service and a button to Android's settings;
 *  3. per-app toggles: profile reading (Hinge only) and the redacted inspector;
 *  4. the inspector: last snapshots with their verdict, export as a file or as shared text;
 *  5. captured profiles: name, field count, time, upload and link state, with Show fields,
 *     "Which match is this?", Upload again and Delete.
 * Plain framework views, like HingeExperimentalActivity. FLAG_SECURE keeps captured profile text
 * out of screenshots and the recents thumbnail. This screen only shows text and starts Android's
 * own settings, file picker and share sheet.
 */
class ScreenReaderActivity : Activity() {
    private lateinit var column: LinearLayout
    private var message: String? = null
    private val expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 80) }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        // Opening Wingman's screen ends any viewing in progress, so the list below is current.
        val core = ScreenReaderRuntime.core(this)
        ScreenReaderRuntime.runInBackground {
            core.flush()
            ScreenReaderRuntime.sync()?.syncAll()
            runOnUiThread { if (!isDestroyed) render() }
        }
        render()
    }

    private fun heading(text: String) = column.addView(TextView(this).apply { this.text = text; textSize = 20f; setPadding(0, 48, 0, 12) })
    private fun para(text: String) = column.addView(TextView(this).apply { this.text = text; setPadding(0, 10, 0, 0) })
    private fun button(text: String, enabled: Boolean = true, onClick: () -> Unit) =
        column.addView(Button(this).apply { this.text = text; isEnabled = enabled; setOnClickListener { onClick() } })

    private fun toggle(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) =
        column.addView(
            CheckBox(this).apply {
                this.text = text
                isChecked = checked
                isEnabled = enabled
                // Posted: the handler rebuilds this list, which must not happen inside the click callback.
                setOnCheckedChangeListener { _, value -> column.post { onChange(value) } }
            },
        )

    private fun render() {
        val core = ScreenReaderRuntime.core(this)
        val settings = core.settings
        column.removeAllViews()
        message?.let { para(it) }

        heading(ScreenReaderExplainer.TITLE)
        ScreenReaderExplainer.BODY.forEach { para("• $it") }
        toggle(ScreenReaderExplainer.ACCEPT_LABEL, settings.explainerAccepted, true) { accepted ->
            ScreenReaderRuntime.setExplainerAccepted(this, accepted)
            show(if (accepted) "Thanks. You can now switch profile reading on." else "Profile reading is off.")
        }

        heading("Android accessibility service")
        val serviceOn = ScreenReaderRuntime.isServiceEnabled(this)
        para(if (serviceOn) "Status: ON. Wingman reads only the apps switched on below." else "Status: OFF. Nothing is read until you switch \"Wingman screen reader\" on in Android's settings.")
        button("Open Android Accessibility settings") { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        para("If Android says the setting is restricted (sideloaded apps, Android 13+): open Android Settings > Apps > Wingman, tap the menu (three dots) and choose \"Allow restricted settings\", then come back.")
        button("Open Wingman's app info") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }

        heading("Read profiles")
        for (pkg in ScreenReaderApps.CAPTURE) {
            toggle("Read match profiles in ${ScreenReaderApps.label(pkg)}", settings.captureEnabled(pkg), settings.explainerAccepted) { on ->
                val ok = ScreenReaderRuntime.setCaptureEnabled(this, pkg, on)
                show(
                    when {
                        !on -> "Reading ${ScreenReaderApps.label(pkg)} is off. Wingman stopped reading."
                        ok -> "Reading ${ScreenReaderApps.label(pkg)} is on. Open a match's profile there, scroll through it, then come back."
                        else -> "Tick \"${ScreenReaderExplainer.ACCEPT_LABEL}\" first."
                    },
                )
            }
        }
        if (!settings.explainerAccepted) para("Tick \"${ScreenReaderExplainer.ACCEPT_LABEL}\" above to enable this.")

        heading("Screen inspector")
        para(ScreenReaderExplainer.INSPECTOR_NOTE)
        for (pkg in ScreenReaderApps.INSPECTOR) {
            toggle("Inspect ${ScreenReaderApps.label(pkg)}", settings.inspectorEnabled(pkg), true) { on ->
                ScreenReaderRuntime.setInspectorEnabled(this, pkg, on)
                show(if (on) "Inspector is on for ${ScreenReaderApps.label(pkg)}. Open the screens you want to check, then come back." else "Inspector is off for ${ScreenReaderApps.label(pkg)}.")
            }
        }
        val snapshots = core.inspector.snapshots()
        para(if (snapshots.isEmpty()) "No snapshots yet." else "Last ${snapshots.size} snapshot(s), newest last:")
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM)
        snapshots.forEach { para("${time.format(Date(it.capturedAtMillis))}  ${it.summary()}") }
        button("Save export as a JSON file", snapshots.isNotEmpty()) { saveExport() }
        button("Share export (redacted text)", snapshots.isNotEmpty()) { shareExport() }
        button("Clear snapshots", snapshots.isNotEmpty()) {
            core.inspector.clear()
            show("Snapshots cleared.")
        }

        heading("Captured profiles")
        val profiles = core.profiles.all()
        if (profiles.isEmpty()) para("None yet. Switch reading on, then open a match's profile in the app and scroll through it.")
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (p in profiles) renderProfile(p, stamp)
    }

    private fun renderProfile(p: CapturedProfile, stamp: DateFormat) {
        val name = p.displayName ?: "(name not seen)"
        column.addView(TextView(this).apply { text = name; textSize = 17f; setPadding(0, 36, 0, 0) })
        para("${p.fieldCount} field(s) · ${ScreenReaderApps.label(packageOf(p.platform))} · ${stamp.format(Date(p.lastSeenMillis))}")
        para(
            when (p.uploadState) {
                UploadState.UPLOADED -> if (p.needsUpload) "Changed since the last upload." else "Uploaded to your Wingman account."
                UploadState.LOCAL_ONLY -> "Saved on this phone. Not uploaded yet."
                UploadState.WAITING_FOR_CONNECTION, UploadState.FAILED -> p.uploadNote ?: "Not uploaded."
            },
        )
        para(
            when {
                p.linkedConversationId != null -> "Linked to ${p.linkedConversationName ?: "a conversation"} (by ${p.linkedBy ?: "you"}). Drafts for that conversation can use it."
                p.matchKey.ambiguous -> "Not linked. More than one match could have this name, so Wingman will not guess."
                else -> "Not linked. Wingman never links by name alone: tell it which match this is."
            },
        )
        if (p.localId in expanded) {
            p.prompts.forEach { para("  ${it.question}\n    ${it.answer}") }
            p.basics.forEach { (k, v) -> para("  $k: ${v.value}") }
            if (p.unlabeled.isNotEmpty()) para("  Seen but not uploaded (Wingman doesn't know what these are): ${p.unlabeled.joinToString(" · ")}")
        }
        button(if (p.localId in expanded) "Hide fields" else "Show fields") {
            if (!expanded.remove(p.localId)) expanded += p.localId
            render()
        }
        if (p.linkedConversationId == null) {
            button("Which match is this?") { pickMatch(p.localId) }
        } else {
            button("Unlink") { act("Unlinked.", "Could not unlink. Try again.") { ScreenReaderRuntime.sync()?.unlink(p.localId) == true } }
        }
        if (p.needsUpload || p.uploadState != UploadState.UPLOADED) {
            button("Upload again") {
                act("Uploaded.", "Not uploaded yet. See the note on the profile.") {
                    ScreenReaderRuntime.sync()?.upload(p.localId)?.uploadState == UploadState.UPLOADED
                }
            }
        }
        button("Delete") {
            AlertDialog.Builder(this)
                .setMessage("Delete this captured profile from this phone and from your Wingman account? Drafts will stop using it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> delete(p.localId) }
                .show()
        }
    }

    private fun packageOf(platform: String): String = ScreenReaderApps.ALL.firstOrNull { ScreenReaderApps.platformOf(it) == platform } ?: platform

    private fun delete(localId: String) {
        val sync = ScreenReaderRuntime.sync()
        if (sync == null) {
            ScreenReaderRuntime.core(this).profiles.remove(localId)
            show("Deleted from this phone.")
            return
        }
        ScreenReaderRuntime.runInBackground {
            val text = when (sync.delete(localId)) {
                DeleteOutcome.DELETED -> "Deleted from this phone and from your account."
                DeleteOutcome.DELETED_LOCALLY_SERVER_PENDING -> "Deleted from this phone. The copy in your account will be deleted as soon as Wingman can reach the server."
                DeleteOutcome.NOT_FOUND -> "It was already deleted."
            }
            runOnUiThread { if (!isDestroyed) show(text) }
        }
    }

    /** One tap: a list of this app's conversations, the ones with the same name first. */
    private fun pickMatch(localId: String) {
        val sync = ScreenReaderRuntime.sync()
        if (sync == null) {
            show("Open Wingman from its main screen first, so it is connected to your account.")
            return
        }
        ScreenReaderRuntime.runInBackground {
            val decision = sync.linkDecision(localId)
            val candidates: List<LinkCandidate> = when (decision) {
                is LinkDecision.Ask -> decision.candidates
                is LinkDecision.Auto -> emptyList()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (decision is LinkDecision.Auto) {
                    ScreenReaderRuntime.runInBackground {
                        sync.upload(localId)
                        runOnUiThread { if (!isDestroyed) show("Linked by its matching ID.") }
                    }
                    return@runOnUiThread
                }
                if (candidates.isEmpty()) {
                    show("Wingman has no conversation from this app yet. A conversation appears after the match's first message arrives as a notification.")
                    return@runOnUiThread
                }
                val labels = candidates.map { it.conversation.contactName + if (it.sameName) "  (same name)" else "" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Which match is this?")
                    .setItems(labels) { _, index ->
                        act("Linked. Drafts for that conversation can now use this profile.", "Could not link. Check the upload note and try again.") {
                            sync.linkByUser(localId, candidates[index].conversation)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun act(done: String, failed: String, action: suspend () -> Boolean) {
        ScreenReaderRuntime.runInBackground {
            val ok = runCatching { action() }.getOrDefault(false)
            runOnUiThread { if (!isDestroyed) show(if (ok) done else failed) }
        }
    }

    private fun show(text: String) {
        message = text
        render()
    }

    private fun exportText(): String = ScreenReaderRuntime.core(this).inspector.exportJson(System.currentTimeMillis())

    private fun saveExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "wingman-screen-inspector.json")
        }
        runCatching { @Suppress("DEPRECATION") startActivityForResult(intent, REQUEST_SAVE) }.onFailure { show("No app on this phone can save a file. Use Share instead.") }
    }

    private fun shareExport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Wingman screen inspector export (redacted)")
            putExtra(Intent.EXTRA_TEXT, exportText())
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share the redacted export")) }.onFailure { show("No app on this phone can share text.") }
    }

    @Deprecated("Plain framework activity result, as used with ACTION_CREATE_DOCUMENT")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(exportText().toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        show(if (ok) "Export saved." else "Could not save the export.")
    }

    private companion object {
        const val REQUEST_SAVE = 4107
    }
}

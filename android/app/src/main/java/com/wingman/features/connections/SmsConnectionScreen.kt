package com.wingman.features.connections

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wingman.core.model.Verification
import com.wingman.core.ui.compose.wingmanViewModel
import com.wingman.integrations.sms.SmsPairingManager
import com.wingman.integrations.sms.android.SmsConnectorRuntime
import com.wingman.integrations.sms.android.SmsRoleHelper
import kotlinx.coroutines.Dispatchers

/*
 * I-020 Compose layer: the "Phone SMS" section on the Settings screen. Compiled by CI's Android job;
 * never run on a device (I-003). All rules live in SmsConnectionState.kt (JVM-tested).
 */

class SmsConnectionsViewModel(app: Application) : ViewModel() {
    val holder = SmsConnectionsState(
        scope = viewModelScope,
        host = SmsConnectorRuntime.host(app),
        device = { SmsRoleHelper.deviceState(app) },
        displayName = SmsPairingManager.displayNameFor(Build.MANUFACTURER, Build.MODEL),
        io = Dispatchers.IO,
        // A new or removed pairing takes effect now: upload, claim (if paired) and record leftovers.
        afterChange = { SmsConnectorRuntime.syncInBackground(app) },
    )

    init {
        holder.verify()
    }
}

/** Settings section: pair, show what the server stored, unpair. */
@Composable
fun SmsConnectionSection() {
    val app = LocalContext.current.applicationContext as Application
    val vm = wingmanViewModel(key = "sms-connection") { SmsConnectionsViewModel(app) }
    val ui by vm.holder.state.collectAsStateWithLifecycle()
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.holder.refresh() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.holder.refresh() }
    LaunchedEffect(Unit) { vm.holder.refresh() }
    SmsConnectionContent(
        ui = ui,
        onRequestRole = {
            val intent = SmsRoleHelper.requestRoleIntent(app)
            if (intent != null) roleLauncher.launch(intent) else vm.holder.refresh()
        },
        onRequestPermissions = {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS))
        },
        onPair = vm.holder::pair,
        onCheck = vm.holder::verify,
        onRequestUnpair = vm.holder::requestUnpair,
        onConfirmUnpair = vm.holder::confirmUnpair,
        onCancelUnpair = vm.holder::cancelUnpair,
    )
}

@Composable
fun SmsConnectionContent(
    ui: SmsConnectionsUi,
    onRequestRole: () -> Unit,
    onRequestPermissions: () -> Unit,
    onPair: () -> Unit,
    onCheck: () -> Unit,
    onRequestUnpair: () -> Unit,
    onConfirmUnpair: () -> Unit,
    onCancelUnpair: () -> Unit,
) {
    val v = ui.view
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phone SMS", style = MaterialTheme.typography.titleMedium)
            Text("Status: ${v.statusLabel}", style = MaterialTheme.typography.bodyMedium)
            v.displayName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            v.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (v.paired) {
                v.sendingNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    if (v.autopilotAvailable) "Autopilot: available for SMS" else "Autopilot: not available for SMS",
                    style = MaterialTheme.typography.labelLarge,
                )
                v.capabilities.forEach { row ->
                    Column {
                        Text("${row.name}: ${row.label}", style = MaterialTheme.typography.bodySmall,
                            color = if (row.status == Verification.VERIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(row.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                when (ui.step) {
                    SmsPairStep.NO_TELEPHONY -> Text(SmsConnectionCopy.NO_TELEPHONY, style = MaterialTheme.typography.bodySmall)
                    SmsPairStep.NEEDS_ROLE -> {
                        Text(SmsConnectionCopy.ROLE_EXPLANATION, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onRequestRole, enabled = ui.busy == null) { Text("Make Wingman the default SMS app") }
                    }
                    SmsPairStep.NEEDS_PERMISSIONS -> {
                        Text(SmsConnectionCopy.PERMISSIONS_EXPLANATION, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onRequestPermissions, enabled = ui.busy == null) { Text("Allow SMS access") }
                    }
                    SmsPairStep.READY -> {
                        Text(SmsConnectionCopy.PAIR_EXPLANATION, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onPair, enabled = ui.busy == null && ui.backendAvailable) { Text("Pair this phone's SMS") }
                    }
                }
                if (!ui.backendAvailable) Text("Not connected to a Wingman server.", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "${v.lastSyncLabel} · waiting to upload: ${v.local.pendingEnvelopes} · couldn't be delivered to Wingman: ${v.local.deadLetters}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (v.paired) {
                    OutlinedButton(onClick = onCheck, enabled = ui.busy == null) { Text("Check status") }
                }
                if (v.paired || v.staleConnectionId != null) {
                    OutlinedButton(onClick = onRequestUnpair, enabled = ui.busy == null && ui.backendAvailable) { Text("Disconnect") }
                }
                if (ui.busy != null) CircularProgressIndicator(Modifier.padding(4.dp))
            }
        }
    }
    if (ui.confirmingUnpair) {
        AlertDialog(
            onDismissRequest = onCancelUnpair,
            title = { Text(SmsConnectionCopy.UNPAIR_TITLE) },
            text = { Text(SmsConnectionCopy.UNPAIR_BODY) },
            confirmButton = { TextButton(onClick = onConfirmUnpair) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = onCancelUnpair) { Text("Keep connected") } },
        )
    }
}

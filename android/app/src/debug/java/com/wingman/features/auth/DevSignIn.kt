package com.wingman.features.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/*
 * DEBUG SOURCE SET ONLY (I-011). The release source set has its own DevSignIn.kt with
 * DEV_SIGN_IN_AVAILABLE = false and no panel; DevSignInSourceSetTest checks that.
 *
 * Accepts a token minted on the developer's machine with the backend dev-stub CLI
 * (`uv run python -m app.security.dev_stub --sub alice`). The dev SECRET is never in the app:
 * only the pasted, already-signed token is stored (encrypted), and AuthSession.signInWithDevToken
 * also refuses unless BuildConfig.DEBUG.
 */

internal const val DEV_SIGN_IN_AVAILABLE: Boolean = true

internal val devSignInPanel: DevSignInPanel? = { busy, error, onSubmit ->
    // remember, not rememberSaveable: the token must not go into the saved-instance-state bundle.
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Developer sign-in (debug build only)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste a token from: cd backend && uv run python -m app.security.dev_stub --sub alice",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Dev token") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { onSubmit(token) }, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Use dev token")
        }
    }
}

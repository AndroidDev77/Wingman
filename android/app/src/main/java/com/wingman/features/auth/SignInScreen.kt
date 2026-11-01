package com.wingman.features.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wingman.BuildConfig
import com.wingman.core.auth.AuthSession
import com.wingman.core.auth.SignOutReason
import com.wingman.core.auth.android.AndroidAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * I-011 sign-in screen. Compiled by CI's Android job; not run on a device. Real sign-in is OIDC
 * authorization code + PKCE in the system browser (AppAuth); there is no password field and no
 * WebView. The rules live in AuthGate (JVM-tested) and core/auth.
 */

/**
 * Debug-only dev-token panel. The `debug` source set provides it (features/auth/DevSignIn.kt);
 * the `release` source set provides null, so release APKs contain no dev-token UI at all.
 */
typealias DevSignInPanel = @Composable (busy: Boolean, error: String?, onSubmit: (String) -> Unit) -> Unit

@Composable
fun SignInScreen(session: AuthSession, reason: SignOutReason) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val oidc = remember { AndroidAuth.oidc(context) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devError by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val client = oidc ?: return@rememberLauncherForActivityResult
        scope.launch {
            val (tokens, message) = if (result.resultCode == Activity.RESULT_CANCELED && result.data == null) {
                null to "Sign-in was cancelled."
            } else {
                client.completeSignIn(result.data)
            }
            if (tokens != null) session.signIn(tokens) else error = message
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Sign in to Wingman", style = MaterialTheme.typography.headlineSmall)
            AuthGate.message(reason)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (oidc != null) {
                Text(
                    "You'll sign in on your identity provider's page in the browser. Wingman never sees your password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                launcher.launch(oidc.authorizationIntent())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Could not reach the sign-in service. Check your connection and try again."
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue in browser") }
            } else {
                Text(AuthGate.OIDC_NOT_CONFIGURED, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            // Present only in debug builds (debug source set); null in release.
            devSignInPanel?.let { panel ->
                HorizontalDivider()
                panel(busy, devError) { raw ->
                    busy = true
                    scope.launch {
                        val rejection = session.signInWithDevToken(raw, debugBuild = BuildConfig.DEBUG)
                        devError = rejection?.message
                        busy = false
                    }
                }
            }
        }
    }
}

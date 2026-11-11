package com.wingman.features.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wingman.BuildConfig
import com.wingman.core.auth.AuthSession
import com.wingman.core.auth.SignOutReason
import com.wingman.core.auth.android.AndroidAuth
import com.wingman.core.ui.compose.BannerTone
import com.wingman.core.ui.compose.InfoBanner
import com.wingman.core.ui.compose.SectionCard
import com.wingman.ui.theme.Spacing
import com.wingman.ui.theme.WingmanTheme
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
    // Only the browser flow shows a spinner on its button; a dev-token sign-in also sets [busy].
    var browserPending by remember { mutableStateOf(false) }
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
            browserPending = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        SignInLayout(
            reasonMessage = AuthGate.message(reason),
            oidcAvailable = oidc != null,
            busy = busy,
            browserPending = browserPending,
            error = error,
            onContinue = {
                val client = oidc ?: return@SignInLayout
                busy = true
                browserPending = true
                error = null
                scope.launch {
                    try {
                        launcher.launch(client.authorizationIntent())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Could not reach the sign-in service. Check your connection and try again."
                        busy = false
                        browserPending = false
                    }
                }
            },
            // Present only in debug builds (debug source set); null in release.
            devPanel = devSignInPanel?.let { panel ->
                {
                    panel(busy, devError) { raw ->
                        busy = true
                        scope.launch {
                            val rejection = session.signInWithDevToken(raw, debugBuild = BuildConfig.DEBUG)
                            devError = rejection?.message
                            busy = false
                        }
                    }
                }
            },
        )
    }
}

/** Stateless sign-in layout, so previews can show it without an auth session. */
@Composable
fun SignInLayout(
    reasonMessage: String?,
    oidcAvailable: Boolean,
    busy: Boolean,
    error: String?,
    onContinue: () -> Unit,
    browserPending: Boolean = busy,
    devPanel: (@Composable () -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Brand header.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Text("Sign in to Wingman", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Your AI assistant for dating conversations.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        reasonMessage?.let { InfoBanner(it) }

        SectionCard {
            if (oidcAvailable) {
                Text(
                    "You'll sign in on your identity provider's page in the browser. Wingman never sees your password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onContinue,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget),
                ) {
                    if (browserPending) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text("Continue in browser")
                }
            } else {
                Text(AuthGate.OIDC_NOT_CONFIGURED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { InfoBanner(it, tone = BannerTone.ERROR) }
        }

        devPanel?.let { panel ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(Spacing.lg)) { panel() }
            }
        }
    }
}

@Preview(name = "Sign in (OIDC)")
@Composable
private fun SignInPreview() = WingmanTheme {
    Surface { SignInLayout(reasonMessage = null, oidcAvailable = true, busy = false, error = null, onContinue = {}, devPanel = null) }
}

@Preview(name = "Sign in: not configured, dev panel (dark)", heightDp = 900)
@Composable
private fun SignInDevDarkPreview() = WingmanTheme(darkTheme = true) {
    Surface {
        SignInLayout(
            reasonMessage = "Your session ended. Please sign in again.",
            oidcAvailable = false,
            busy = false,
            error = null,
            onContinue = {},
            devPanel = devSignInPanel?.let { panel -> { panel(false, null) {} } },
        )
    }
}

@Preview(name = "Sign in: dev panel (light)", heightDp = 900)
@Composable
private fun SignInDevPreview() = WingmanTheme {
    Surface {
        SignInLayout(
            reasonMessage = null,
            oidcAvailable = true,
            busy = false,
            error = "Sign-in was cancelled.",
            onContinue = {},
            devPanel = devSignInPanel?.let { panel -> { panel(false, "That token is not valid.") {} } },
        )
    }
}

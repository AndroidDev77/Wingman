package com.wingman.features.auth

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.wingman.core.ui.compose.LocalAppContainer
import kotlinx.coroutines.launch

/**
 * Sign-out control for the Settings screen (I-011; the integrator places it, Settings is WING-013's).
 * Clears the encrypted session; the app then shows the sign-in screen. Renders nothing in mock-API
 * builds, which have no session.
 */
@Composable
fun SignOutButton(modifier: Modifier = Modifier) {
    val session = LocalAppContainer.current.auth ?: return
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = { scope.launch { session.signOut() } }, modifier = modifier) { Text("Sign out") }
}

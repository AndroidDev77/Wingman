package com.wingman.features.auth

import com.wingman.core.auth.AuthState
import com.wingman.core.auth.SignOutReason

/**
 * Pure rules for the sign-in gate (I-011). `WingmanApp` shows the sign-in screen instead of the
 * main shell while [signInReason] is non-null. Mock-API builds have no session at all
 * (`AppContainer.auth == null`), so they never see the gate.
 */
object AuthGate {
    /** Null when the app may show its main graph; otherwise why the user must sign in. */
    fun signInReason(state: AuthState?): SignOutReason? = (state as? AuthState.SignedOut)?.reason

    /** Line shown above the sign-in options, or null for a plain first sign-in. */
    fun message(reason: SignOutReason): String? = when (reason) {
        SignOutReason.NO_SESSION -> null
        SignOutReason.USER -> "You signed out."
        SignOutReason.SESSION_EXPIRED -> "Your session ended. Please sign in again."
    }

    const val OIDC_NOT_CONFIGURED =
        "Sign-in is not available in this build: Auth0 is not configured yet (I-014). " +
            "See docs/auth0.md."
}

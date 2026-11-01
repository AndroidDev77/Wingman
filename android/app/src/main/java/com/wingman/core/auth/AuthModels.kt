package com.wingman.core.auth

import kotlinx.serialization.Serializable

/*
 * I-011 auth core. Pure Kotlin (no android.* imports) so every rule here is JVM-tested.
 * Android pieces (Keystore cipher, AppAuth, SharedPreferences) live in core/auth/android.
 */

/** Where a session's tokens came from. */
@Serializable
enum class TokenSource {
    /** OIDC authorization code + PKCE through AppAuth in the system browser. */
    OIDC,

    /** DEBUG BUILDS ONLY: a token minted with the backend dev-stub CLI and pasted in. Never refreshable. */
    DEV,
}

/**
 * One session's tokens. [toString] is redacted: a token must never reach a log line, an exception
 * message or a crash report, even through string interpolation of this object.
 */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Access-token expiry in epoch milliseconds; null if neither the IdP nor the JWT says. */
    val expiresAtEpochMs: Long? = null,
    val source: TokenSource,
) {
    override fun toString(): String =
        "AuthTokens(source=$source, expiresAtEpochMs=$expiresAtEpochMs, hasRefreshToken=${refreshToken != null})"
}

/** Why the user is signed out. Drives the message on the sign-in screen. */
enum class SignOutReason {
    /** No stored session (first launch, or the store was unreadable). */
    NO_SESSION,

    /** The user chose to sign out. */
    USER,

    /** The session could not be renewed: refresh rejected, no refresh token, or the API rejected a fresh token. */
    SESSION_EXPIRED,
}

sealed interface AuthState {
    data class SignedIn(val source: TokenSource) : AuthState
    data class SignedOut(val reason: SignOutReason) : AuthState
}

/** Outcome of exchanging a refresh token. */
sealed interface RefreshResult {
    data class Success(val tokens: AuthTokens) : RefreshResult

    /** The IdP refused the refresh token (e.g. `invalid_grant`): the session is over. */
    data object Rejected : RefreshResult

    /** Network or IdP outage: the session may still be valid, so it is kept. */
    data object Unavailable : RefreshResult
}

/** Exchanges a refresh token for new tokens (AppAuth on Android; fakes in tests). */
fun interface TokenRefresher {
    suspend fun refresh(refreshToken: String): RefreshResult

    companion object {
        /** For sessions that cannot refresh (dev tokens, or OIDC not configured). */
        val NONE = TokenRefresher { RefreshResult.Rejected }
    }
}

/**
 * Redacted auth event log. Implementations must only ever receive the fixed messages the auth
 * package writes; callers never interpolate a token (enforced by the sentinel test).
 */
fun interface AuthLogger {
    fun log(message: String)

    companion object {
        val NONE = AuthLogger { }
    }
}

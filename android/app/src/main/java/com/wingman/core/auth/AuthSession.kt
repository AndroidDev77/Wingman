package com.wingman.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's one signed-in session and its [TokenProvider] (I-011). Pure Kotlin.
 *
 * - Tokens live in [store] (encrypted at rest on Android) and in memory; they never reach [logger].
 * - [accessToken] refreshes when the token is within [skewMs] of `exp` (or past it).
 * - All refreshes run under one [Mutex] and re-check the current token first, so N concurrent
 *   callers that saw the same stale token cause exactly one [TokenRefresher.refresh] call.
 * - Refresh outcomes: success replaces the tokens; [RefreshResult.Rejected] (or no refresh token)
 *   signs out with [SignOutReason.SESSION_EXPIRED]; [RefreshResult.Unavailable] (offline, IdP
 *   outage) keeps the session and fails only the current request, so a network blip never logs
 *   the user out.
 */
class AuthSession(
    private val store: TokenStore,
    private val refresher: TokenRefresher = TokenRefresher.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMs: Long = DEFAULT_SKEW_MS,
    private val logger: AuthLogger = AuthLogger.NONE,
) : TokenProvider {

    private val mutex = Mutex()

    @Volatile private var tokens: AuthTokens? = store.load()

    private val _state = MutableStateFlow<AuthState>(
        tokens?.let { AuthState.SignedIn(it.source) } ?: AuthState.SignedOut(SignOutReason.NO_SESSION),
    )

    /** Observed by the UI: [AuthState.SignedOut] shows the sign-in screen. */
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = tokens != null

    // ------------------------------------------------------------------ TokenProvider

    override suspend fun accessToken(): String? {
        val current = tokens ?: return null
        if (!needsRefresh(current)) return current.accessToken
        return mutex.withLock { refreshLocked(observedAccessToken = current.accessToken, afterUnauthorized = false) }
    }

    override suspend fun refreshAfterUnauthorized(rejectedAccessToken: String): String? = mutex.withLock {
        refreshLocked(observedAccessToken = rejectedAccessToken, afterUnauthorized = true)
            ?.takeIf { it != rejectedAccessToken }
    }

    override suspend fun onRetryUnauthorized(rejectedAccessToken: String) = mutex.withLock {
        // A token that was fresh (or just refreshed) was refused too: the backend does not accept
        // this session (deleted account, wrong audience, revoked). Retrying cannot help.
        if (tokens?.accessToken == rejectedAccessToken) {
            logger.log("auth: refreshed token rejected by API; signing out")
            clearLocked(SignOutReason.SESSION_EXPIRED)
        }
    }

    override suspend fun signOut() = mutex.withLock {
        logger.log("auth: signed out by user")
        clearLocked(SignOutReason.USER)
    }

    // ------------------------------------------------------------------ sign-in

    /** Stores a freshly obtained session (OIDC code exchange, or an accepted dev token). */
    suspend fun signIn(newTokens: AuthTokens) = mutex.withLock {
        store.save(newTokens)
        tokens = newTokens
        logger.log("auth: signed in (source=${newTokens.source})")
        _state.value = AuthState.SignedIn(newTokens.source)
    }

    /**
     * DEBUG BUILDS ONLY: signs in with a token minted by the backend dev-stub CLI. Returns the
     * rejection, or null on success. Refused outright unless [debugBuild] is true.
     */
    suspend fun signInWithDevToken(raw: String, debugBuild: Boolean): DevToken.Rejection? =
        when (val r = DevToken.accept(raw, debugBuild, clock())) {
            is DevToken.Result.Accepted -> { signIn(r.tokens); null }
            is DevToken.Result.Rejected -> r.reason
        }

    // ------------------------------------------------------------------ internals

    internal fun needsRefresh(t: AuthTokens): Boolean {
        val exp = t.expiresAtEpochMs ?: return false
        return clock() >= exp - skewMs
    }

    private fun isExpired(t: AuthTokens): Boolean = t.expiresAtEpochMs?.let { clock() >= it } ?: false

    /** Must hold [mutex]. Returns the token to use now, or null. */
    private suspend fun refreshLocked(observedAccessToken: String, afterUnauthorized: Boolean): String? {
        val current = tokens ?: return null
        // Someone else refreshed (or signed in again) while we waited for the lock: use theirs.
        if (current.accessToken != observedAccessToken) return current.accessToken
        // Proactive path: a concurrent caller may have refreshed into a token that is still fresh.
        if (!afterUnauthorized && !needsRefresh(current)) return current.accessToken

        val refreshToken = current.refreshToken
        if (refreshToken == null || current.source == TokenSource.DEV) {
            // Nothing to refresh with. Keep using the token until it actually expires or is refused.
            return if (afterUnauthorized || isExpired(current)) {
                logger.log("auth: session cannot be refreshed; signing out")
                clearLocked(SignOutReason.SESSION_EXPIRED)
                null
            } else {
                current.accessToken
            }
        }

        logger.log("auth: refreshing access token")
        return when (val r = refresher.refresh(refreshToken)) {
            is RefreshResult.Success -> {
                // Some IdPs omit the refresh token on refresh: keep the old one then.
                val next = r.tokens.copy(refreshToken = r.tokens.refreshToken ?: refreshToken)
                store.save(next)
                tokens = next
                logger.log("auth: refresh succeeded")
                next.accessToken
            }
            RefreshResult.Rejected -> {
                logger.log("auth: refresh rejected; signing out")
                clearLocked(SignOutReason.SESSION_EXPIRED)
                null
            }
            RefreshResult.Unavailable -> {
                logger.log("auth: refresh unavailable; keeping session")
                // After a 401 the old token is known bad: fail this request. Otherwise keep using
                // it while it is still inside its validity window.
                if (afterUnauthorized || isExpired(current)) null else current.accessToken
            }
        }
    }

    private fun clearLocked(reason: SignOutReason) {
        store.clear()
        tokens = null
        _state.value = AuthState.SignedOut(reason)
    }

    companion object {
        /** Refresh this long before `exp`: covers clock skew and request latency (backend leeway is 30 s). */
        const val DEFAULT_SKEW_MS: Long = 60_000
    }
}

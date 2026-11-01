package com.wingman.core.auth

/**
 * The bearer-token source the HTTP client asks on every request (I-011). Pure Kotlin.
 *
 * Contract for implementations:
 *  - [accessToken] returns the current access token, refreshing it first when it is within the
 *    skew margin of its expiry; null when signed out.
 *  - Refreshes are single-flight: concurrent callers wait for one refresh and share its result.
 *  - [refreshAfterUnauthorized] is called at most once per request (the server answered 401 to
 *    [rejectedAccessToken]); it returns a DIFFERENT token to retry with, or null to give up.
 *  - [onRetryUnauthorized] is called when the retried request was also answered 401: the session
 *    is not accepted, so the provider signs out. The request is never retried again.
 */
interface TokenProvider {
    suspend fun accessToken(): String?
    suspend fun refreshAfterUnauthorized(rejectedAccessToken: String): String?
    suspend fun onRetryUnauthorized(rejectedAccessToken: String)
    suspend fun signOut()
}

/**
 * Adapter for a plain token lambda (tests, previews, the legacy `HttpWingmanApi(tokenProvider = ...)`
 * constructor). Never refreshes, so a 401 is returned to the caller unchanged.
 */
class StaticTokenProvider(private val token: () -> String?) : TokenProvider {
    override suspend fun accessToken(): String? = token()
    override suspend fun refreshAfterUnauthorized(rejectedAccessToken: String): String? = null
    override suspend fun onRetryUnauthorized(rejectedAccessToken: String) = Unit
    override suspend fun signOut() = Unit
}

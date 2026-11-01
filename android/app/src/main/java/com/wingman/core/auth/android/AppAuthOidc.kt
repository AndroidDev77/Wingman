package com.wingman.core.auth.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wingman.core.auth.AuthLogger
import com.wingman.core.auth.AuthTokens
import com.wingman.core.auth.Jwt
import com.wingman.core.auth.OidcConfig
import com.wingman.core.auth.RefreshResult
import com.wingman.core.auth.TokenRefresher
import com.wingman.core.auth.TokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume

/**
 * OIDC authorization code + PKCE in the system browser (Custom Tabs) through AppAuth (I-011).
 * AppAuth generates the PKCE verifier/challenge (S256), `state` and `nonce`, and checks them and
 * the ID token on the code exchange. No WebView, no password field. Compiled by CI; never run
 * against a real IdP (none is chosen, I-014): UNVERIFIED.
 */
class AppAuthOidc(
    context: Context,
    private val config: OidcConfig,
    private val logger: AuthLogger = AuthLogger.NONE,
) : TokenRefresher {

    private val service = AuthorizationService(context.applicationContext)
    private val discoveryLock = Mutex()
    @Volatile private var discovered: AuthorizationServiceConfiguration? = null

    /** Discovery document (`<issuer>/.well-known/openid-configuration`), fetched once per process. */
    private suspend fun configuration(): AuthorizationServiceConfiguration = discoveryLock.withLock {
        discovered ?: suspendCancellableCoroutine<AuthorizationServiceConfiguration> { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer)) { cfg, ex ->
                if (cfg != null) {
                    cont.resume(cfg)
                } else {
                    cont.resumeWith(Result.failure(ex ?: IllegalStateException("discovery failed")))
                }
            }
        }.also { discovered = it }
    }

    /** The intent that opens the IdP's authorization page in the browser. Throws if discovery fails. */
    suspend fun authorizationIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            configuration(),
            config.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(config.redirectUri),
        )
            .setScopes(config.scopes)
            .apply { config.audience?.let { setAdditionalParameters(mapOf("audience" to it)) } }
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Completes sign-in from the activity result: the new tokens, or null and a user-facing message. */
    suspend fun completeSignIn(data: Intent?): Pair<AuthTokens?, String?> {
        if (data == null) return null to "Sign-in was cancelled."
        val response = AuthorizationResponse.fromIntent(data)
        val error = AuthorizationException.fromIntent(data)
        if (response == null) {
            logger.log("auth: authorization failed (type=${error?.type}, code=${error?.code})")
            return null to "Sign-in was cancelled or refused."
        }
        return when (val r = token(response.createTokenExchangeRequest())) {
            is TokenOutcome.Ok -> {
                logger.log("auth: code exchange succeeded")
                r.tokens to null
            }
            is TokenOutcome.Failed -> {
                logger.log("auth: code exchange failed (type=${r.ex.type}, code=${r.ex.code})")
                null to "Could not complete sign-in. Try again."
            }
        }
    }

    override suspend fun refresh(refreshToken: String): RefreshResult {
        val cfg = try {
            configuration()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RefreshResult.Unavailable
        }
        val request = TokenRequest.Builder(cfg, config.clientId)
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(refreshToken)
            .build()
        return when (val r = token(request)) {
            is TokenOutcome.Ok -> RefreshResult.Success(r.tokens)
            // invalid_grant / invalid_client etc.: the IdP answered and refused. Anything else
            // (network, server error) leaves the session in place.
            is TokenOutcome.Failed ->
                if (r.ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) RefreshResult.Rejected else RefreshResult.Unavailable
        }
    }

    private sealed interface TokenOutcome {
        data class Ok(val tokens: AuthTokens) : TokenOutcome
        data class Failed(val ex: AuthorizationException) : TokenOutcome
    }

    private suspend fun token(request: TokenRequest): TokenOutcome = suspendCancellableCoroutine { cont ->
        service.performTokenRequest(request) { response, ex ->
            val tokens = response?.toTokens()
            cont.resume(
                if (tokens != null) {
                    TokenOutcome.Ok(tokens)
                } else {
                    TokenOutcome.Failed(ex ?: AuthorizationException.GeneralErrors.SERVER_ERROR)
                },
            )
        }
    }

    private fun TokenResponse.toTokens(): AuthTokens? {
        val access = accessToken ?: return null
        return AuthTokens(
            accessToken = access,
            refreshToken = refreshToken,
            expiresAtEpochMs = accessTokenExpirationTime ?: Jwt.expiryEpochMs(access),
            source = TokenSource.OIDC,
        )
    }
}

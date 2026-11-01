package com.wingman.core.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/*
 * OkHttp wiring for [TokenProvider] (I-011). Pure JVM; tested with MockWebServer.
 *
 * OkHttp calls interceptors and authenticators on its own worker threads (Retrofit suspend calls
 * use `enqueue`), so bridging to the suspend provider with runBlocking does not block the main
 * thread or a coroutine dispatcher.
 */

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** Adds `Authorization: Bearer <token>` when the provider has a token; sends no header otherwise. */
class BearerTokenInterceptor(private val provider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { provider.accessToken() }
        val request = chain.request().newBuilder()
            .apply { if (!token.isNullOrBlank()) header(AUTHORIZATION, BEARER + token) }
            .build()
        return chain.proceed(request)
    }
}

/**
 * On `401`: refresh once and retry once. A second `401` on the retried request signs out (via
 * [TokenProvider.onRetryUnauthorized]) and is returned to the caller. Never loops: OkHttp calls
 * this once per 401 in a call's follow-up chain, and this returns null from the second one on.
 * A 401 on a request that carried no bearer token is returned as-is (nothing to refresh).
 */
class BearerTokenAuthenticator(private val provider: TokenProvider) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val sent = response.request.header(AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER) }
            ?.removePrefix(BEARER)
            ?: return null
        if (unauthorizedCount(response) > 1) {
            runBlocking { provider.onRetryUnauthorized(sent) }
            return null
        }
        val fresh = runBlocking { provider.refreshAfterUnauthorized(sent) } ?: return null
        return response.request.newBuilder().header(AUTHORIZATION, BEARER + fresh).build()
    }

    private fun unauthorizedCount(response: Response): Int {
        var n = 0
        var r: Response? = response
        while (r != null) {
            if (r.code == 401) n++
            r = r.priorResponse
        }
        return n
    }
}

/** Installs the interceptor and authenticator for [provider] on this builder. */
fun OkHttpClient.Builder.bearerAuth(provider: TokenProvider): OkHttpClient.Builder =
    addInterceptor(BearerTokenInterceptor(provider)).authenticator(BearerTokenAuthenticator(provider))

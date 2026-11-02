package com.wingman.core.auth

import java.net.URI
import java.net.URISyntaxException

/**
 * OIDC client settings for AppAuth, from build config (`-Pwingman.oidc.*`, docs/android_auth.md).
 * No identity provider is chosen yet (I-014), so the default build has none and real sign-in is
 * disabled; the sign-in screen says so.
 */
data class OidcConfig(
    /** Exact `iss` of the IdP; must equal the backend's `WINGMAN_AUTH_ISSUER`. Discovery is `<issuer>/.well-known/openid-configuration`. */
    val issuer: String,
    val clientId: String,
    /** Custom-scheme or claimed https redirect registered at the IdP for this public client. */
    val redirectUri: String,
    val scopes: List<String>,
    /**
     * Optional `audience` authorization parameter. Auth0 needs it (the API Identifier) to issue a
     * JWT access token for the backend; without it the access token is opaque (docs/auth0.md).
     */
    val audience: String? = null,
    /**
     * Optional `connection` authorization parameter (Auth0), e.g. `google-oauth2`, to skip the
     * Universal Login picker and go straight to that identity provider.
     */
    val connection: String? = null,
) {
    /** Scheme for AppAuth's `RedirectUriReceiverActivity` (manifest placeholder `appAuthRedirectScheme`). */
    val redirectScheme: String get() = URI(redirectUri).scheme

    /**
     * Extra authorization-request parameters for AppAuth's `setAdditionalParameters`: `audience`
     * and `connection` when configured, nothing otherwise. Never contains a parameter AppAuth sets
     * itself (`scope`, `redirect_uri`, `state`, PKCE...).
     */
    fun additionalAuthorizationParameters(): Map<String, String> = buildMap {
        audience?.let { put("audience", it) }
        connection?.let { put("connection", it) }
    }

    /** The `scope` request value, space-separated, as AppAuth sends it. */
    val scopeString: String get() = scopes.joinToString(" ")

    companion object {
        /**
         * Null (sign-in disabled) when issuer, client ID or redirect URI is blank. Throws
         * [IllegalArgumentException] for a set-but-invalid value so a bad build fails loudly.
         */
        fun fromBuildConfig(
            issuer: String,
            clientId: String,
            redirectUri: String,
            scopes: String,
            audience: String = "",
            connection: String = "",
        ): OidcConfig? {
            if (issuer.isBlank() || clientId.isBlank() || redirectUri.isBlank()) return null
            val iss = parse(issuer)
            require(iss.scheme == "https" && !iss.host.isNullOrEmpty()) { "OIDC issuer must be an https URL" }
            val redirect = parse(redirectUri)
            require(!redirect.scheme.isNullOrEmpty()) { "OIDC redirect URI needs a scheme" }
            val scopeList = scopes.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require("openid" in scopeList) { "OIDC scopes must include openid" }
            val conn = connection.trim().ifEmpty { null }
            require(conn == null || CONNECTION_NAME.matches(conn)) { "OIDC connection must be a connection name like google-oauth2" }
            return OidcConfig(
                issuer.trim(),
                clientId.trim(),
                redirectUri.trim(),
                scopeList,
                audience.trim().ifEmpty { null },
                conn,
            )
        }

        private fun parse(value: String): URI = try {
            URI(value.trim())
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("not a valid URI")
        }

        const val DEFAULT_SCOPES = "openid profile email offline_access"

        /** Redirect URI the docs and Auth0 Native app use; its scheme matches the manifest default. */
        const val DEFAULT_REDIRECT_URI = "com.wingman.auth:/oauth2redirect"

        /** Scheme `app/build.gradle.kts` uses for `appAuthRedirectScheme` when no redirect URI is set. */
        const val DEFAULT_REDIRECT_SCHEME = "com.wingman.auth"

        /** Auth0 connection names: letters, digits, `-` and `_` (e.g. `google-oauth2`). */
        private val CONNECTION_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

        /**
         * Mirror of the `appAuthRedirectScheme` placeholder logic in `app/build.gradle.kts`: the
         * text before the first `:` of the redirect URI, or [DEFAULT_REDIRECT_SCHEME] when empty.
         */
        fun manifestRedirectScheme(redirectUri: String): String =
            redirectUri.trim().substringBefore(':', missingDelimiterValue = "").ifEmpty { DEFAULT_REDIRECT_SCHEME }
    }
}

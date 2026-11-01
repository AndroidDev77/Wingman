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
    /** Optional `audience` authorization parameter, for IdPs that need it to issue an API-audience token. */
    val audience: String? = null,
) {
    /** Scheme for AppAuth's `RedirectUriReceiverActivity` (manifest placeholder `appAuthRedirectScheme`). */
    val redirectScheme: String get() = URI(redirectUri).scheme

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
        ): OidcConfig? {
            if (issuer.isBlank() || clientId.isBlank() || redirectUri.isBlank()) return null
            val iss = parse(issuer)
            require(iss.scheme == "https" && !iss.host.isNullOrEmpty()) { "OIDC issuer must be an https URL" }
            val redirect = parse(redirectUri)
            require(!redirect.scheme.isNullOrEmpty()) { "OIDC redirect URI needs a scheme" }
            val scopeList = scopes.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require("openid" in scopeList) { "OIDC scopes must include openid" }
            return OidcConfig(issuer.trim(), clientId.trim(), redirectUri.trim(), scopeList, audience.trim().ifEmpty { null })
        }

        private fun parse(value: String): URI = try {
            URI(value.trim())
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("not a valid URI")
        }

        const val DEFAULT_SCOPES = "openid profile email offline_access"
    }
}

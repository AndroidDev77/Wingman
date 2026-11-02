package com.wingman.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Auth0 readiness (I-014): the AppAuth authorization request built from build config.
 * Pure Kotlin: [OidcConfig.additionalAuthorizationParameters] is exactly what `AppAuthOidc`
 * passes to `AuthorizationRequest.Builder.setAdditionalParameters`.
 */
class Auth0RequestTest {
    private val tenant = "https://wingman-test.us.auth0.com/"
    private val clientId = "aBcDeFgHiJkLmNoPqRsTuVwXyZ012345"
    private val apiId = "https://api.wingman.test"

    private fun auth0(audience: String = apiId, connection: String = "", scopes: String = OidcConfig.DEFAULT_SCOPES) =
        OidcConfig.fromBuildConfig(tenant, clientId, OidcConfig.DEFAULT_REDIRECT_URI, scopes, audience, connection)!!

    @Test fun auth0RequestSendsAudienceAndOfflineAccessScopes() {
        val c = auth0()
        assertEquals(tenant, c.issuer) // trailing slash kept: discovery and iss match Auth0 exactly
        assertEquals(clientId, c.clientId)
        assertEquals(listOf("openid", "profile", "email", "offline_access"), c.scopes)
        assertEquals("openid profile email offline_access", c.scopeString)
        assertEquals(mapOf("audience" to apiId), c.additionalAuthorizationParameters())
    }

    @Test fun connectionAddedOnlyWhenConfigured() {
        val withGoogle = auth0(connection = "google-oauth2")
        assertEquals("google-oauth2", withGoogle.connection)
        assertEquals(
            mapOf("audience" to apiId, "connection" to "google-oauth2"),
            withGoogle.additionalAuthorizationParameters(),
        )
        assertNull(auth0().connection)
        assertTrue("connection" !in auth0().additionalAuthorizationParameters())
        // Blank or whitespace means "not set".
        assertNull(auth0(connection = "   ").connection)
    }

    @Test fun noExtraParametersWhenNothingConfigured() {
        val plain = auth0(audience = "")
        assertNull(plain.audience)
        assertNull(plain.connection)
        assertTrue(plain.additionalAuthorizationParameters().isEmpty())
        // Connection without audience is passed alone (Auth0 would then issue an opaque token;
        // docs/auth0.md troubleshooting), never invented.
        assertEquals(mapOf("connection" to "google-oauth2"), auth0(audience = "", connection = "google-oauth2").additionalAuthorizationParameters())
    }

    @Test fun extraParametersNeverShadowAppAuthBuiltIns() {
        val builtIns = setOf(
            "client_id", "code_challenge", "code_challenge_method", "display", "login_hint", "prompt",
            "ui_locales", "redirect_uri", "response_mode", "response_type", "scope", "state", "nonce", "claims",
        )
        val keys = auth0(connection = "google-oauth2").additionalAuthorizationParameters().keys
        assertTrue(keys.none { it in builtIns })
    }

    @Test fun invalidConnectionRejected() {
        for (bad in listOf("google oauth2", "google-oauth2&prompt=none", "https://x", "-lead", "a".repeat(129))) {
            assertThrows(bad, IllegalArgumentException::class.java) { auth0(connection = bad) }
        }
        assertEquals("Username-Password-Authentication", auth0(connection = "Username-Password-Authentication").connection)
    }

    @Test fun unconfiguredBuildStaysDisabledWithConnectionSet() {
        // Mock / dev-token builds set nothing; a stray connection alone must not enable sign-in.
        assertNull(OidcConfig.fromBuildConfig("", "", "", OidcConfig.DEFAULT_SCOPES, "", "google-oauth2"))
        assertNull(OidcConfig.fromBuildConfig("", "", "", OidcConfig.DEFAULT_SCOPES))
    }

    @Test fun redirectMatchesManifestPlaceholder() {
        val c = auth0()
        assertEquals("com.wingman.auth:/oauth2redirect", c.redirectUri)
        assertEquals("com.wingman.auth", c.redirectScheme)
        assertEquals(c.redirectScheme, OidcConfig.manifestRedirectScheme(c.redirectUri))
        // Unconfigured builds fall back to the same scheme, so the documented URI always matches.
        assertEquals(OidcConfig.DEFAULT_REDIRECT_SCHEME, OidcConfig.manifestRedirectScheme(""))
        assertEquals("com.example.other", OidcConfig.manifestRedirectScheme("com.example.other:/cb"))
    }

    @Test fun gradleWiringMatchesKotlinMirror() {
        val gradle = File(appDir(), "build.gradle.kts").readText()
        // The manifest placeholder expression and default that manifestRedirectScheme mirrors.
        assertTrue(gradle.contains("""oidcRedirect.substringBefore(':', missingDelimiterValue = "").ifEmpty { "${OidcConfig.DEFAULT_REDIRECT_SCHEME}" }"""))
        assertTrue(gradle.contains("""manifestPlaceholders["appAuthRedirectScheme"]"""))
        // Default scopes and the new optional connection property.
        assertTrue(gradle.contains("""prop("wingman.oidc.scopes", "${OidcConfig.DEFAULT_SCOPES}")"""))
        assertTrue(gradle.contains("""buildConfigField("String", "OIDC_AUDIENCE", quoted(prop("wingman.oidc.audience")))"""))
        assertTrue(gradle.contains("""buildConfigField("String", "OIDC_CONNECTION", quoted(prop("wingman.oidc.connection")))"""))
        // AndroidAuth passes both to OidcConfig, and AppAuthOidc sends the map.
        val main = File(appDir(), "src/main/java/com/wingman/core/auth/android")
        assertTrue(File(main, "AndroidAuth.kt").readText().contains("connection = BuildConfig.OIDC_CONNECTION"))
        assertTrue(File(main, "AppAuthOidc.kt").readText().contains("config.additionalAuthorizationParameters()"))
    }

    private fun appDir(): File {
        val candidates = listOf(File("."), File("app"), File("android/app"))
        return candidates.firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main/java/com/wingman").isDirectory }
            ?: error("cannot find android/app from ${File(".").absolutePath}")
    }
}

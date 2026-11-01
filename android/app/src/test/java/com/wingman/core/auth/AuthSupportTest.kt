package com.wingman.core.auth

import com.wingman.core.di.AppConfig
import com.wingman.core.di.AppContainer
import com.wingman.features.auth.AuthGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthSupportTest {
    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------ dev token (debug-only path)

    @Test fun devTokenRefusedWhenNotDebugBuild() {
        val jwt = fakeJwt(now / 1000 + 3600)
        assertEquals(DevToken.Result.Rejected(DevToken.Rejection.NOT_DEBUG_BUILD), DevToken.accept(jwt, debugBuild = false, nowEpochMs = now))
    }

    @Test fun devTokenSignInRefusedWhenNotDebugBuild() = runTest {
        val store = InMemoryTokenStore()
        val s = AuthSession(store, clock = { now })
        assertEquals(DevToken.Rejection.NOT_DEBUG_BUILD, s.signInWithDevToken(fakeJwt(now / 1000 + 3600), debugBuild = false))
        assertFalse(s.isSignedIn)
        assertNull(store.load())
    }

    @Test fun devTokenValidation() {
        fun reason(raw: String) = (DevToken.accept(raw, true, now) as? DevToken.Result.Rejected)?.reason
        assertEquals(DevToken.Rejection.EMPTY, reason("   "))
        assertEquals(DevToken.Rejection.NOT_A_JWT, reason("not-a-jwt"))
        assertEquals(DevToken.Rejection.NOT_A_JWT, reason("a.b.c"))
        assertEquals(DevToken.Rejection.NO_EXPIRY, reason(fakeJwt(null)))
        assertEquals(DevToken.Rejection.EXPIRED, reason(fakeJwt(now / 1000 - 1)))

        val jwt = fakeJwt(now / 1000 + 3600)
        val ok = DevToken.accept("  Bearer $jwt\n", true, now) as DevToken.Result.Accepted
        assertEquals(jwt, ok.tokens.accessToken)
        assertEquals(TokenSource.DEV, ok.tokens.source)
        assertNull(ok.tokens.refreshToken)
        assertEquals((now / 1000 + 3600) * 1000, ok.tokens.expiresAtEpochMs)
    }

    /**
     * Build check for the dev path: the paste UI must exist only in the debug source set, and the
     * release source set must disable it. (CI also compiles the release Kotlin; see build.gradle.kts.)
     */
    @Test fun devSignInSourceSetsAreSplitByBuildType() {
        val src = appSrcDir()
        val debug = File(src, "debug/java/com/wingman/features/auth/DevSignIn.kt").readText()
        val release = File(src, "release/java/com/wingman/features/auth/DevSignIn.kt").readText()
        assertTrue(debug.contains("DEV_SIGN_IN_AVAILABLE: Boolean = true"))
        assertTrue(release.contains("DEV_SIGN_IN_AVAILABLE: Boolean = false"))
        assertTrue(release.contains("devSignInPanel: DevSignInPanel? = null"))
        assertFalse("release must not contain a paste field", release.contains("TextField"))

        // Nothing in main may define the panel, and nothing anywhere may carry the dev secret.
        val main = File(src, "main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(main.none { it.readText().contains("val devSignInPanel") })
        val all = src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { !it.path.contains("/test/") }
        assertTrue(all.none { it.readText().contains("DEV_SECRET") })
    }

    private fun appSrcDir(): File {
        // JUnit runs with the module dir (android/app) as the working directory in Gradle and the local verifier.
        val candidates = listOf(File("src"), File("app/src"), File("android/app/src"))
        return candidates.firstOrNull { File(it, "main/java/com/wingman").isDirectory }
            ?: error("cannot find android/app/src from ${File(".").absolutePath}")
    }

    // ------------------------------------------------------------ JWT, tokens, store

    @Test fun jwtExpiryParsing() {
        assertEquals(1_900_000_000_000L, Jwt.expiryEpochMs(fakeJwt(1_900_000_000)))
        assertNull(Jwt.expiryEpochMs(fakeJwt(null)))
        assertNull(Jwt.expiryEpochMs("x.y"))
        assertNull(Jwt.expiryEpochMs("a.%%%.c"))
    }

    @Test fun tokensToStringIsRedacted() {
        val t = AuthTokens("ACCESS-SECRET", "REFRESH-SECRET", 5, TokenSource.OIDC)
        assertFalse(t.toString().contains("SECRET"))
        assertTrue(t.toString().contains("hasRefreshToken=true"))
    }

    @Test fun encryptedStoreRoundTripsAndNeverWritesPlaintext() {
        val blobs = MemoryBlobStore()
        val store = EncryptedTokenStore(XorCipher(), blobs)
        val t = AuthTokens("ACCESS-PLAIN", "REFRESH-PLAIN", 42, TokenSource.OIDC)
        store.save(t)
        val raw = blobs.value!!
        assertFalse(raw.contains("PLAIN"))
        assertFalse(java.util.Base64.getDecoder().decode(raw).decodeToString().contains("PLAIN"))
        assertEquals(t, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test fun unreadableStoreIsClearedAndSignedOut() {
        val blobs = MemoryBlobStore("AAAA") // decodes, but the cipher rejects it
        val s = AuthSession(EncryptedTokenStore(XorCipher(), blobs), clock = { now })
        assertFalse(s.isSignedIn)
        assertNull(blobs.value)
        assertEquals(1, blobs.deletes)
    }

    // ------------------------------------------------------------ OIDC config

    @Test fun oidcConfigDisabledWhenUnconfigured() {
        assertNull(OidcConfig.fromBuildConfig("", "", "", OidcConfig.DEFAULT_SCOPES))
        assertNull(OidcConfig.fromBuildConfig("https://idp.example", "", "com.wingman.auth:/cb", OidcConfig.DEFAULT_SCOPES))
    }

    @Test fun oidcConfigParsesAndValidates() {
        val c = OidcConfig.fromBuildConfig("https://idp.example/realm", "wingman-android", "com.wingman.auth:/oauth2redirect", "openid, profile offline_access openid", "wingman-api")!!
        assertEquals(listOf("openid", "profile", "offline_access"), c.scopes)
        assertEquals("com.wingman.auth", c.redirectScheme)
        assertEquals("wingman-api", c.audience)
        assertNull(OidcConfig.fromBuildConfig("https://idp.example", "c", "x:/cb", "openid")!!.audience)

        assertThrows(IllegalArgumentException::class.java) { OidcConfig.fromBuildConfig("http://idp.example", "c", "x:/cb", "openid") }
        assertThrows(IllegalArgumentException::class.java) { OidcConfig.fromBuildConfig("https://idp.example", "c", "x:/cb", "profile") }
        assertThrows(IllegalArgumentException::class.java) { OidcConfig.fromBuildConfig("https://idp.example", "c", "/cb", "openid") }
    }

    // ------------------------------------------------------------ app wiring

    @Test fun gateShowsSignInOnlyWhenSignedOut() {
        assertNull(AuthGate.signInReason(null)) // mock build: no session, no gate
        assertNull(AuthGate.signInReason(AuthState.SignedIn(TokenSource.DEV)))
        assertEquals(SignOutReason.SESSION_EXPIRED, AuthGate.signInReason(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED)))
        assertNull(AuthGate.message(SignOutReason.NO_SESSION))
        assertNotNull(AuthGate.message(SignOutReason.SESSION_EXPIRED))
    }

    @Test fun mockBuildsNeedNoSignIn() {
        val session = AuthSession(InMemoryTokenStore(), clock = { now })
        assertNull(AppContainer(AppConfig("http://10.0.2.2:8000", useMockApi = true), authSession = session).auth)
        assertNull(AppContainer(AppConfig("http://10.0.2.2:8000", useMockApi = false)).auth)
        assertSame(session, AppContainer(AppConfig("http://10.0.2.2:8000", useMockApi = false), authSession = session).auth)
    }
}

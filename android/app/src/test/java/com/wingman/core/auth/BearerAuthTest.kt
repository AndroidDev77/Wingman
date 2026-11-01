package com.wingman.core.auth

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.http.HttpWingmanApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** I-011 interceptor + authenticator against MockWebServer, with a real [AuthSession]. */
class BearerAuthTest {
    private lateinit var server: MockWebServer
    private val clock = FakeClock()
    private val hour = 3_600_000L

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun tokens(access: String, refresh: String? = "rt-1") =
        AuthTokens(access, refresh, clock.now + hour, TokenSource.OIDC)

    private fun session(initial: AuthTokens?, refresher: TokenRefresher, logger: AuthLogger = AuthLogger.NONE) =
        AuthSession(InMemoryTokenStore(initial), refresher, clock.fn, logger = logger)

    private fun client(provider: TokenProvider) =
        OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).bearerAuth(provider).build()

    private fun get(c: OkHttpClient) = c.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().use { it.code }

    private fun ok() = MockResponse().setResponseCode(200).setBody("{}")
    private fun unauthorized() = MockResponse().setResponseCode(401)
        .setHeader("Content-Type", "application/problem+json")
        .setHeader("WWW-Authenticate", "Bearer")
        .setBody("""{"type":"about:blank","title":"Unauthorized","status":401,"code":"unauthenticated"}""")

    @Test fun headerAddedOnlyWhenTokenExists() {
        val s = session(tokens("at-1"), FakeRefresher { error("no refresh expected") })
        val c = client(s)
        server.enqueue(ok()); server.enqueue(ok())

        assertEquals(200, get(c))
        assertEquals("Bearer at-1", server.takeRequest().getHeader("Authorization"))

        runTest { s.signOut() }
        assertEquals(200, get(c))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun unauthorizedRefreshesOnceAndRetriesOnce() {
        val r = FakeRefresher { RefreshResult.Success(tokens("at-2", "rt-2")) }
        val s = session(tokens("at-1"), r)
        server.enqueue(unauthorized()); server.enqueue(ok())

        assertEquals(200, get(client(s)))
        assertEquals(2, server.requestCount)
        assertEquals("Bearer at-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer at-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, r.calls)
        assertTrue(s.isSignedIn)
    }

    @Test fun secondUnauthorizedDoesNotLoopAndSignsOut() {
        val r = FakeRefresher { RefreshResult.Success(tokens("at-${it.length + 1}")) }
        val s = session(tokens("at-1"), r)
        repeat(5) { server.enqueue(unauthorized()) } // a looping client would consume these

        assertEquals(401, get(client(s)))
        assertEquals(2, server.requestCount) // original + exactly one retry
        assertEquals(1, r.calls)
        assertFalse(s.isSignedIn)
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED), s.state.value)
    }

    @Test fun refreshFailureSignsOutWithoutRetry() {
        val s = session(tokens("at-1"), FakeRefresher { RefreshResult.Rejected })
        server.enqueue(unauthorized()); server.enqueue(ok())

        assertEquals(401, get(client(s)))
        assertEquals(1, server.requestCount)
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED), s.state.value)
    }

    @Test fun unauthorizedWithoutTokenIsReturnedAsIs() {
        val r = FakeRefresher { error("no refresh expected") }
        val s = session(null, r)
        server.enqueue(unauthorized()); server.enqueue(ok())

        assertEquals(401, get(client(s)))
        assertEquals(1, server.requestCount)
        assertEquals(0, r.calls)
    }

    @Test fun httpWingmanApiSurfacesUnauthenticatedAfterFailedRefresh() = runTest {
        val s = session(tokens("at-1"), FakeRefresher { RefreshResult.Rejected })
        val api = HttpWingmanApi(server.url("/").toString(), s, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
        server.enqueue(unauthorized())

        val r = api.getMe()
        val err = (r as ApiResult.Failure).error as ApiError.Http
        assertEquals(401, err.status)
        assertEquals("unauthenticated", err.problem?.code)
        assertFalse(s.isSignedIn)
        assertEquals("application/json, application/problem+json", server.takeRequest().getHeader("Accept"))
    }

    @Test fun staticProviderNeverRetries() {
        server.enqueue(unauthorized()); server.enqueue(ok())
        assertEquals(401, get(client(StaticTokenProvider { "fixed" })))
        assertEquals(1, server.requestCount)
    }

    /** Sentinel: no token value may appear in anything the auth package logs, across every path. */
    @Test fun tokensNeverAppearInLogs() {
        val access1 = "SENTINEL-ACCESS-1-${System.nanoTime()}"
        val access2 = "SENTINEL-ACCESS-2-${System.nanoTime()}"
        val refresh = "SENTINEL-REFRESH-${System.nanoTime()}"
        val logger = RecordingLogger()
        val first = AuthTokens(access1, refresh, clock.now + hour, TokenSource.OIDC)
        val s = session(first, FakeRefresher { RefreshResult.Success(AuthTokens(access2, null, clock.now + hour, TokenSource.OIDC)) }, logger)

        server.enqueue(unauthorized()); server.enqueue(unauthorized())
        get(client(s)) // refresh, retry, second 401 -> sign-out
        runTest {
            s.signIn(first)
            s.signOut()
            // Unreadable stored blob: logged by type only.
            val blobs = MemoryBlobStore("not-base64-$access1")
            assertNull(EncryptedTokenStore(XorCipher(), blobs, logger).load())
        }

        val all = logger.lines.joinToString("\n") + "\n" + first.toString()
        assertTrue("expected auth log lines", logger.lines.size >= 5)
        for (secret in listOf(access1, access2, refresh)) {
            assertFalse("token leaked into logs", all.contains(secret))
        }
    }
}

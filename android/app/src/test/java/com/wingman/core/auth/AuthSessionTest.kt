package com.wingman.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionTest {
    private val clock = FakeClock()
    private val minute = 60_000L

    private fun oidc(access: String, expMs: Long?, refresh: String? = "rt-1") =
        AuthTokens(accessToken = access, refreshToken = refresh, expiresAtEpochMs = expMs, source = TokenSource.OIDC)

    private fun session(initial: AuthTokens?, refresher: TokenRefresher, store: TokenStore = InMemoryTokenStore(initial)) =
        AuthSession(store, refresher, clock.fn, skewMs = minute)

    @Test fun signedOutWithoutStoredSession() = runTest {
        val s = session(null, FakeRefresher { error("no refresh expected") })
        assertNull(s.accessToken())
        assertEquals(AuthState.SignedOut(SignOutReason.NO_SESSION), s.state.value)
    }

    @Test fun freshTokenIsReturnedWithoutRefresh() = runTest {
        val r = FakeRefresher { error("no refresh expected") }
        val s = session(oidc("at-1", clock.now + 10 * minute), r)
        assertEquals("at-1", s.accessToken())
        assertEquals(0, r.calls)
        assertEquals(AuthState.SignedIn(TokenSource.OIDC), s.state.value)
    }

    @Test fun refreshesInsideSkewWindowAndPersists() = runTest {
        val store = InMemoryTokenStore(oidc("at-1", clock.now + 10 * minute))
        val r = FakeRefresher { RefreshResult.Success(oidc("at-2", clock.now + 60 * minute, refresh = null)) }
        val s = session(null, r, store)

        clock.now += 8 * minute // 2 minutes left: outside the 1-minute skew
        assertEquals("at-1", s.accessToken())
        assertEquals(0, r.calls)

        clock.now += 61_000 // 59 s left: inside the skew window
        assertEquals("at-2", s.accessToken())
        assertEquals(1, r.calls)
        assertEquals(listOf("rt-1"), r.seen)
        // IdP omitted a new refresh token: the old one is kept, and the new tokens were saved.
        assertEquals("rt-1", store.load()!!.refreshToken)
        assertEquals("at-2", store.load()!!.accessToken)
    }

    @Test fun tokenWithoutExpiryIsNeverProactivelyRefreshed() = runTest {
        val r = FakeRefresher { error("no refresh expected") }
        val s = session(oidc("at-1", null), r)
        clock.now += 365L * 24 * 60 * minute
        assertEquals("at-1", s.accessToken())
    }

    @Test fun concurrentCallersShareOneRefresh() = runTest {
        val r = FakeRefresher { RefreshResult.Success(oidc("at-2", clock.now + 60 * minute)) }
        r.gate = CompletableDeferred()
        val s = session(oidc("at-1", clock.now + 30_000), r) // already inside the skew

        val callers = (1..20).map { async { s.accessToken() } }
        runCurrent() // every caller is now waiting on the one in-flight refresh
        assertEquals(1, r.calls)
        r.gate!!.complete(Unit)
        assertEquals(List(20) { "at-2" }, callers.awaitAll())
        assertEquals(1, r.calls)
    }

    @Test fun concurrentUnauthorizedShareOneRefresh() = runTest {
        val r = FakeRefresher { RefreshResult.Success(oidc("at-2", clock.now + 60 * minute)) }
        r.gate = CompletableDeferred()
        val s = session(oidc("at-1", clock.now + 30 * minute), r)

        val callers = (1..5).map { async { s.refreshAfterUnauthorized("at-1") } }
        runCurrent()
        r.gate!!.complete(Unit)
        assertEquals(List(5) { "at-2" }, callers.awaitAll())
        assertEquals(1, r.calls)
    }

    @Test fun rejectedRefreshSignsOutAndClearsStore() = runTest {
        val store = InMemoryTokenStore(oidc("at-1", clock.now + 10_000))
        val s = session(null, FakeRefresher { RefreshResult.Rejected }, store)
        assertNull(s.accessToken())
        assertNull(store.load())
        assertFalse(s.isSignedIn)
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED), s.state.value)
    }

    @Test fun unavailableRefreshKeepsSessionWhileTokenStillValid() = runTest {
        val r = FakeRefresher { RefreshResult.Unavailable }
        val s = session(oidc("at-1", clock.now + 30_000), r)
        assertEquals("at-1", s.accessToken()) // still valid for 30 s: keep using it
        assertTrue(s.isSignedIn)

        clock.now += 31_000 // expired, IdP still unreachable: no token, but not signed out
        assertNull(s.accessToken())
        assertTrue(s.isSignedIn)

        // After a 401 an unavailable refresh fails the request but keeps the session.
        assertNull(s.refreshAfterUnauthorized("at-1"))
        assertTrue(s.isSignedIn)
    }

    @Test fun unauthorizedWithoutRefreshTokenSignsOut() = runTest {
        val s = session(oidc("at-1", clock.now + 30 * minute, refresh = null), FakeRefresher { error("no refresh expected") })
        assertNull(s.refreshAfterUnauthorized("at-1"))
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED), s.state.value)
    }

    @Test fun staleUnauthorizedUsesTokenAlreadyRefreshedByAnotherCaller() = runTest {
        val r = FakeRefresher { error("no refresh expected") }
        val s = session(oidc("at-2", clock.now + 30 * minute), r)
        assertEquals("at-2", s.refreshAfterUnauthorized("at-1"))
        assertEquals(0, r.calls)
    }

    @Test fun refreshReturningSameTokenIsNotRetried() = runTest {
        val r = FakeRefresher { RefreshResult.Success(oidc("at-1", clock.now + 60 * minute)) }
        val s = session(oidc("at-1", clock.now + 30 * minute), r)
        assertNull(s.refreshAfterUnauthorized("at-1"))
    }

    @Test fun retryUnauthorizedSignsOutOnlyForCurrentToken() = runTest {
        val s = session(oidc("at-2", clock.now + 30 * minute), FakeRefresher { error("no refresh expected") })
        s.onRetryUnauthorized("at-1") // an older token: someone already moved on
        assertTrue(s.isSignedIn)
        s.onRetryUnauthorized("at-2")
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED), s.state.value)
    }

    @Test fun signInAndSignOut() = runTest {
        val store = InMemoryTokenStore()
        val s = session(null, FakeRefresher { error("no refresh expected") }, store)
        s.signIn(oidc("at-1", clock.now + 30 * minute))
        assertEquals("at-1", s.accessToken())
        assertEquals("at-1", store.load()!!.accessToken)
        s.signOut()
        assertNull(s.accessToken())
        assertNull(store.load())
        assertEquals(AuthState.SignedOut(SignOutReason.USER), s.state.value)
    }

    @Test fun devTokenIsUsedUntilExpiryAndNeverRefreshed() = runTest {
        val r = FakeRefresher { error("dev tokens never refresh") }
        val s = session(null, r)
        val jwt = fakeJwt(expSeconds = (clock.now + 12 * 60 * minute) / 1000)
        assertNull(s.signInWithDevToken(jwt, debugBuild = true))
        assertEquals(AuthState.SignedIn(TokenSource.DEV), s.state.value)

        clock.now += 12 * 60 * minute - 30_000 // inside the skew: nothing to refresh with, keep it
        assertEquals(jwt, s.accessToken())
        clock.now += 30_000 // expired
        assertNull(s.accessToken())
        assertEquals(AuthState.SignedOut(SignOutReason.SESSION_EXPIRED), s.state.value)
        assertEquals(0, r.calls)
    }
}

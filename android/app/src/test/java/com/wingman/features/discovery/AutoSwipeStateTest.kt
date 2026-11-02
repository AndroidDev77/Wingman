package com.wingman.features.discovery

import com.wingman.core.api.ApiResult
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.Capability
import com.wingman.core.model.CapabilitySet
import com.wingman.core.model.Connection
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionSettingsUpdate
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Verification
import com.wingman.features.conversation.settle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** WING-031 Settings > Auto-swipe: disabled and explained unless the server stores a VERIFIED swipe. */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoSwipeStateTest {
    class Api(val mock: MockWingmanApi = MockWingmanApi()) : WingmanApi by mock {
        val puts = mutableListOf<Pair<String, Boolean>>()
        override suspend fun putConnectionSettings(connectionId: String, body: ConnectionSettingsUpdate): ApiResult<Connection> {
            puts += connectionId to body.autoSwipeEnabled
            return mock.putConnectionSettings(connectionId, body)
        }
    }

    private suspend fun pair(api: Api, platform: String, swipe: Verification = Verification.UNVERIFIED): Connection {
        val caps = ConnectorCapabilities(
            "connector_capabilities.v1", platform, "0.1.0",
            CapabilitySet(Capability(Verification.UNVERIFIED), Capability(Verification.UNSUPPORTED), Capability(Verification.UNSUPPORTED), Capability(swipe), Capability(Verification.UNSUPPORTED)),
            Instant.parse("2026-11-01T18:00:00Z"),
        )
        return api.mock.createConnection(
            ConnectionCreateRequest(platform, platform, "0192f3a0-0000-4000-8000-0000000031bb", caps, ConnectionTransport.NOTIFICATION),
            IdempotencyKey("pair-$platform-0001"),
        ).getOrNull()!!
    }

    private fun TestScope.holder(api: Api) = AutoSwipeState(backgroundScope, api).also { it.load(); settle() }

    @Test fun noDatingConnectionsExplainsThatNoPlatformSwipes() = runTest {
        val s = holder(Api())
        assertTrue(s.state.value.rows.isEmpty()) // sms / mock / notifications are not dating apps
        assertEquals(AutoSwipeCopy.NO_CONNECTIONS, s.state.value.emptyExplanation)
    }

    @Test fun everyRealConnectionTodayIsDisabledWithTheReason() = runTest {
        val api = Api()
        val tinder = pair(api, "tinder", Verification.UNSUPPORTED)
        pair(api, "hinge", Verification.VERIFIED) // a client claim: the server stores UNVERIFIED
        val s = holder(api)
        val rows = s.state.value.rows
        assertEquals(listOf("Tinder", "Hinge"), rows.map { it.platformLabel })
        assertTrue(rows.none { it.toggleEnabled || it.checked })
        assertTrue(rows[0].explanation.contains("Tinder swipe is UNSUPPORTED"))
        assertTrue(rows[1].explanation.contains("Hinge swipe is UNVERIFIED"))
        s.toggle(tinder.id, true)
        settle()
        assertTrue("refused locally, no request", api.puts.isEmpty())
    }

    @Test fun anOperatorVerifiedSwipeCanBeTurnedOnAndAlwaysOff() = runTest {
        val api = Api()
        val hinge = pair(api, "hinge")
        api.mock.operatorVerifySwipeForTest(hinge.id) // test fixture: no real platform has this
        val s = holder(api)
        assertTrue(s.state.value.rows.single().toggleEnabled)
        s.toggle(hinge.id, true)
        settle()
        assertTrue(s.state.value.rows.single().checked)
        s.toggle(hinge.id, false)
        settle()
        assertFalse(s.state.value.rows.single().checked)
        assertEquals(listOf(hinge.id to true, hinge.id to false), api.puts)
    }

    @Test fun aServerRefusalIsShown() = runTest {
        val api = object : WingmanApi by MockWingmanApi() {
            override suspend fun putConnectionSettings(connectionId: String, body: ConnectionSettingsUpdate): ApiResult<Connection> =
                MockWingmanApi.problem(422, ErrorCode.SWIPE_EXECUTION_DISABLED, "no")
        }
        val inner = Api()
        val hinge = pair(inner, "hinge")
        inner.mock.operatorVerifySwipeForTest(hinge.id)
        val conns = inner.mock.listConnections().getOrNull()!!
        val wrapped = object : WingmanApi by api {
            override suspend fun listConnections(page: com.wingman.core.api.PageRequest) = ApiResult.Success(conns)
        }
        val s = AutoSwipeState(backgroundScope, wrapped).also { it.load(); settle() }
        s.toggle(hinge.id, true)
        settle()
        assertTrue(s.state.value.message!!.startsWith("Couldn't change auto-swipe"))
        assertFalse(s.state.value.rows.single().checked)
    }
}

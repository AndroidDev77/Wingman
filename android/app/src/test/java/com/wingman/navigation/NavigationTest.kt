package com.wingman.navigation

import com.wingman.core.di.AppConfig
import com.wingman.core.di.AppContainer
import com.wingman.core.di.AppContainerProvider
import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.api.mock.MockWingmanApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    @Test fun allRequiredDestinationsExist() {
        assertEquals(
            setOf("Dashboard", "Inbox", "Conversation", "Settings", "Approvals", "Discovery", "Onboarding"),
            Destination.entries.map { it.name }.toSet(),
        )
        assertEquals(Destination.Dashboard, Destination.START)
        assertFalse(Destination.Conversation in Destination.topLevelDestinations)
        assertFalse(Destination.Onboarding in Destination.topLevelDestinations)
        assertEquals(5, Destination.topLevelDestinations.size)
        assertEquals(Destination.entries.size, Destination.entries.map { it.pattern }.toSet().size)
    }

    @Test fun conversationRouteCarriesValidatedId() {
        val id = "0192f3a0-0000-7000-8000-0000000000b1"
        assertEquals("conversation/$id", Routes.conversation(id))
        assertEquals("conversation/{conversationId}", Destination.Conversation.pattern)
        for (bad in listOf("", "../settings", "0192F3A0-0000-7000-8000-0000000000B1", "abc", "$id/extra")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Routes.conversation(bad) }
        }
    }

    @Test fun routesResolveBackToDestinations() {
        assertEquals(Destination.Conversation, Destination.fromRoute(Routes.conversation("0192f3a0-0000-7000-8000-0000000000b1")))
        assertEquals(Destination.Conversation, Destination.fromRoute(Destination.Conversation.pattern))
        Destination.topLevelDestinations.forEach { assertEquals(it, Destination.fromRoute(it.pattern)) }
        assertNull(Destination.fromRoute("nope"))
        assertNull(Destination.fromRoute(null))
    }

    @Test fun onboardingRouteResolvesAndIsLeftWithoutReturn() {
        assertEquals("onboarding", Routes.onboarding())
        assertEquals(Destination.Onboarding, Destination.fromRoute(Routes.onboarding()))
        assertTrue(FirstLaunch.popsOnLeave(Destination.Onboarding))
        Destination.entries.filter { it != Destination.Onboarding }.forEach { assertFalse(FirstLaunch.popsOnLeave(it)) }
        assertFalse(FirstLaunch.popsOnLeave(null))
    }

    @Test fun registryResolvesAndRejectsDuplicates() {
        val reg = ScreenRegistry<String>().register(Destination.Inbox, "inbox-screen")
        assertEquals("inbox-screen", reg.resolve(Destination.Inbox))
        assertNull(reg.resolve(Destination.Settings))
        assertEquals(setOf(Destination.Inbox), reg.registered())
        assertThrows(IllegalStateException::class.java) { reg.register(Destination.Inbox, "other") }
    }

    @Test fun containerSelectsApiImplementation() {
        assertTrue(AppContainer(AppConfig("http://10.0.2.2:8000", useMockApi = true)).api is MockWingmanApi)
        assertTrue(AppContainer(AppConfig("http://10.0.2.2:8000", useMockApi = false)).api is HttpWingmanApi)
        val mock = MockWingmanApi()
        assertSame(mock, AppContainer.mock(mock).api)
        assertThrows(IllegalArgumentException::class.java) { AppContainer(AppConfig("10.0.2.2:8000", useMockApi = false)) }
    }

    @Test fun containerProviderCreatesOncePerProcess() {
        AppContainerProvider.reset()
        var created = 0
        val a = AppContainerProvider.get { created++; AppContainer.mock() }
        val b = AppContainerProvider.get { created++; AppContainer.mock() }
        assertSame(a, b)
        assertEquals(1, created)
        AppContainerProvider.reset()
    }
}

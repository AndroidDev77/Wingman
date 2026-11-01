package com.wingman.core.di

import com.wingman.AppInfo
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.auth.AuthSession
import com.wingman.core.auth.StaticTokenProvider

/** Build-time configuration for the container. Populated from BuildConfig in MainActivity. */
data class AppConfig(
    val apiBaseUrl: String,
    /** When true the app runs entirely on [MockWingmanApi]: no backend needed. */
    val useMockApi: Boolean,
)

/**
 * Manual DI root (no Hilt, per WING-012). One instance per process, created by MainActivity.
 * Pure Kotlin so it is unit-tested.
 *
 * Feature tickets add their own dependencies by asking the integrator to add a property here
 * (this file is the integrator-owned DI root); they get the container through
 * `LocalAppContainer.current` in Compose, or as a constructor argument in their ViewModel factory.
 */
class AppContainer(
    val config: AppConfig,
    /** Fixed bearer token for tests and previews; ignored when [authSession] is given. */
    tokenProvider: () -> String? = { null },
    apiOverride: WingmanApi? = null,
    /**
     * The signed-in session (I-011): the HTTP client takes its bearer token from here, refreshes it
     * near expiry and on a 401, and signs out when that fails. Built by MainActivity from
     * `AndroidAuth.session(context)` (Keystore-encrypted storage).
     */
    authSession: AuthSession? = null,
) {
    val api: WingmanApi = apiOverride ?: if (config.useMockApi) {
        MockWingmanApi()
    } else {
        HttpWingmanApi(
            baseUrl = AppInfo.normalizeBaseUrl(config.apiBaseUrl),
            tokenProvider = authSession ?: StaticTokenProvider(tokenProvider),
        )
    }

    /**
     * The session that gates the UI, or null when no sign-in is needed: mock-API builds
     * (`USE_MOCK_API=true`) run offline without one.
     */
    val auth: AuthSession? = authSession.takeIf { api !is MockWingmanApi }

    val isMock: Boolean get() = api is MockWingmanApi

    companion object {
        /** Container for previews and tests: offline mock API. */
        fun mock(api: WingmanApi = MockWingmanApi()) =
            AppContainer(AppConfig(apiBaseUrl = "http://localhost/", useMockApi = true), apiOverride = api)
    }
}

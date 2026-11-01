package com.wingman.core.di

import com.wingman.AppInfo
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.api.mock.MockWingmanApi

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
    /** Bearer token source (WING-004 replaces this with the real session store). */
    tokenProvider: () -> String? = { null },
    apiOverride: WingmanApi? = null,
) {
    val api: WingmanApi = apiOverride ?: if (config.useMockApi) {
        MockWingmanApi()
    } else {
        HttpWingmanApi(baseUrl = AppInfo.normalizeBaseUrl(config.apiBaseUrl), tokenProvider = tokenProvider)
    }

    val isMock: Boolean get() = api is MockWingmanApi

    companion object {
        /** Container for previews and tests: offline mock API. */
        fun mock(api: WingmanApi = MockWingmanApi()) =
            AppContainer(AppConfig(apiBaseUrl = "http://localhost/", useMockApi = true), apiOverride = api)
    }
}

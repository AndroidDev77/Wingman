package com.wingman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wingman.core.di.AppConfig
import com.wingman.core.di.AppContainer
import com.wingman.core.di.AppContainerProvider
import com.wingman.navigation.WingmanApp

/** Entry point: builds the DI container once per process and hosts the Compose shell. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainerProvider.get {
            AppContainer(
                AppConfig(
                    apiBaseUrl = BuildConfig.API_BASE_URL,
                    useMockApi = BuildConfig.USE_MOCK_API,
                ),
            )
        }
        setContent { WingmanApp(container) }
    }
}

package com.wingman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wingman.core.auth.android.AndroidAuth
import com.wingman.core.di.AppConfig
import com.wingman.core.di.AppContainer
import com.wingman.core.di.AppContainerProvider
import com.wingman.integrations.notifications.android.NotificationSyncRuntime
import com.wingman.integrations.sms.android.SmsConnectorRuntime
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
                // I-011: Keystore-encrypted session; the API client takes its bearer token from it.
                // Mock-API builds never build one, so they need no sign-in and touch no Keystore.
                authSession = if (BuildConfig.USE_MOCK_API) null else AndroidAuth.session(applicationContext),
            )
        }
        // WING-016 (ADR 0006): the SMS connector uploads its events and claims leased send
        // instructions through the app's API client, which authenticates through the session above
        // (I-011): bearer token, refresh near expiry, one refresh + retry on 401. While signed out
        // its calls answer 401 and envelopes stay queued for the next round.
        // I-020: it does nothing until the user pairs this phone (Settings > Phone SMS). On start the
        // stored connection is checked with getConnection; unpaired elsewhere (404, unpaired_at) or
        // disconnected stops sync and asks the user to pair again. Runs off the main thread.
        SmsConnectorRuntime.connectBackend(container.api)
        SmsConnectorRuntime.startInBackground(this)
        // WING-027/028 (I-033): the Tinder/Bumble notification connections upload recorded events
        // and claim through the same client. Nothing is read until the user turns a platform on in
        // Settings, which pairs it first (transport: notification).
        NotificationSyncRuntime.connectBackend(container.api)
        setContent { WingmanApp(container) }
    }

    override fun onResume() {
        super.onResume()
        // I-033: refreshAccess on every resume. If notification access was revoked while Wingman was
        // in the background, reading, live reply actions and claims stop at once and the capability
        // downgrade is reported; then one sync round runs off the main thread.
        NotificationSyncRuntime.onAppResume(this)
    }
}

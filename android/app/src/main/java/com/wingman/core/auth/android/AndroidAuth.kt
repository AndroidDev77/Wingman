package com.wingman.core.auth.android

import android.content.Context
import android.util.Log
import com.wingman.BuildConfig
import com.wingman.core.auth.AuthLogger
import com.wingman.core.auth.AuthSession
import com.wingman.core.auth.EncryptedTokenStore
import com.wingman.core.auth.OidcConfig
import com.wingman.core.auth.TokenRefresher

/**
 * Process-wide auth objects for the Android app (I-011): one [AuthSession] backed by the
 * Keystore-encrypted store, and the AppAuth client when OIDC is configured in the build.
 * Created lazily by MainActivity; the Compose sign-in screen reads [oidc] from here.
 */
object AndroidAuth {
    private const val TAG = "WingmanAuth"

    /** Only the fixed, token-free messages written by core/auth reach logcat. */
    val logger = AuthLogger { Log.i(TAG, it) }

    /** OIDC settings from build config, or null when no IdP is configured (the default, I-014). */
    val oidcConfig: OidcConfig? by lazy {
        OidcConfig.fromBuildConfig(
            issuer = BuildConfig.OIDC_ISSUER,
            clientId = BuildConfig.OIDC_CLIENT_ID,
            redirectUri = BuildConfig.OIDC_REDIRECT_URI,
            scopes = BuildConfig.OIDC_SCOPES,
            audience = BuildConfig.OIDC_AUDIENCE,
            connection = BuildConfig.OIDC_CONNECTION,
        )
    }

    @Volatile private var oidcClient: AppAuthOidc? = null
    @Volatile private var session: AuthSession? = null

    @Synchronized
    fun oidc(context: Context): AppAuthOidc? {
        oidcClient?.let { return it }
        val cfg = oidcConfig ?: return null
        return AppAuthOidc(context.applicationContext, cfg, logger).also { oidcClient = it }
    }

    @Synchronized
    fun session(context: Context): AuthSession {
        session?.let { return it }
        val store = EncryptedTokenStore(KeystoreSecretCipher(), SharedPrefsBlobStore(context), logger)
        return AuthSession(store, refresher = oidc(context) ?: TokenRefresher.NONE, logger = logger)
            .also { session = it }
    }
}

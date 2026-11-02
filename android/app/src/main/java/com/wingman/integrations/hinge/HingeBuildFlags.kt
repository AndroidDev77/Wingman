package com.wingman.integrations.hinge

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The client identity the reference documents for Hinge's private API (hinge-rs `settings.rs` and
 * the header builders at cffe397). None of these values is committed in Kotlin: they come from the
 * EXPERIMENTAL build's `BuildConfig` (see docs/integrations/hinge.md, "Experimental build"), so a
 * build without them is inert ([isComplete] false).
 *
 * The reference presents itself as the iOS app; no reference documents Android values
 * (dating_platform_matrix.md §4). The connector never "auto-bumps" [appVersion]: a 426 stops it
 * and the owner updates the build property after checking the reference.
 */
data class HingeClientIdentity(
    val hingeBaseUrl: String,
    val sendbirdApiUrl: String,
    val sendbirdWsUrl: String,
    val sendbirdAppId: String,
    val sendbirdSdkVersion: String,
    val appVersion: String,
    val buildNumber: String,
    val osVersion: String,
    val devicePlatform: String,
    val deviceModelCode: String,
    val deviceRegion: String,
    val acceptLanguage: String,
    /** Optional `Origin` header of the WebSocket handshake (empty: not sent). */
    val sendbirdWsOrigin: String = "",
) {
    /** True when every required value is present and every URL parses. */
    val isComplete: Boolean
        get() = listOf(sendbirdAppId, sendbirdSdkVersion, appVersion, buildNumber, osVersion, devicePlatform, deviceModelCode, deviceRegion, acceptLanguage)
            .all { it.isNotBlank() && it.none(Char::isISOControl) } &&
            hingeBaseUrl.toHttpUrlOrNull() != null &&
            sendbirdApiUrl.toHttpUrlOrNull() != null &&
            sendbirdWsUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrlOrNull() != null

    /** `user-agent` of Hinge REST calls (reference: `Hinge/<build> CFNetwork/... Darwin/...`). */
    fun hingeUserAgent(): String = "Hinge/$buildNumber CFNetwork/3859.100.1 Darwin/25.0.0"

    /** Sendbird's `SendBird` header: `<platform>,<os>,<sdk>,<app id>`. */
    fun sendbirdHeader(): String = "$devicePlatform,$osVersion,$sendbirdSdkVersion,$sendbirdAppId"

    fun sendbirdUserAgent(): String = "${devicePlatform}/c$sendbirdSdkVersion///"

    fun sendbirdSdkUserAgent(): String {
        val p = devicePlatform.lowercase()
        return "main_sdk_info=chat/$p/$sendbirdSdkVersion&device_os_platform=$p&os_version=$osVersion"
    }

    /** Sendbird REST `user-agent` (reference: `Jios/<sdk>`). */
    fun sendbirdRestUserAgent(): String = "Jios/$sendbirdSdkVersion"

    override fun toString(): String = "HingeClientIdentity(app=$appVersion, build=$buildNumber, complete=$isComplete)"

    companion object {
        val EMPTY = HingeClientIdentity("", "", "", "", "", "", "", "", "", "", "", "")
    }
}

/**
 * Build-time switches. [experimentalEnabled] is the `wingman.experimental` Gradle property
 * (default OFF). With it off, the connector is inert: no screen, no worker, no Hinge or Sendbird
 * call, and [HingeNetGate] refuses everything. The WING-017 notification plugin is NOT gated: the
 * notification-assisted path ships regardless (D-021 item 7).
 */
data class HingeBuildFlags(
    val experimentalEnabled: Boolean,
    val identity: HingeClientIdentity,
) {
    /** The experimental connector may run: flag on AND a complete identity. */
    val connectorAvailable: Boolean get() = experimentalEnabled && identity.isComplete

    companion object {
        val OFF = HingeBuildFlags(false, HingeClientIdentity.EMPTY)

        /**
         * Reads `com.wingman.BuildConfig` reflectively, so this compiles and stays OFF until the
         * integrator adds the fields (docs/integrations/hinge.md, "Experimental build"). Any field
         * that is missing reads as off/empty.
         */
        fun fromBuildConfig(className: String = "com.wingman.BuildConfig"): HingeBuildFlags {
            val cls = runCatching { Class.forName(className) }.getOrNull() ?: return OFF
            fun bool(name: String) = runCatching { cls.getField(name).getBoolean(null) }.getOrDefault(false)
            fun str(name: String) = runCatching { cls.getField(name).get(null) as? String }.getOrNull().orEmpty()
            val enabled = bool("WINGMAN_EXPERIMENTAL")
            if (!enabled) return OFF
            return HingeBuildFlags(
                experimentalEnabled = true,
                identity = HingeClientIdentity(
                    hingeBaseUrl = str("HINGE_BASE_URL"),
                    sendbirdApiUrl = str("HINGE_SENDBIRD_API_URL"),
                    sendbirdWsUrl = str("HINGE_SENDBIRD_WS_URL"),
                    sendbirdAppId = str("HINGE_SENDBIRD_APP_ID"),
                    sendbirdSdkVersion = str("HINGE_SENDBIRD_SDK_VERSION"),
                    appVersion = str("HINGE_APP_VERSION"),
                    buildNumber = str("HINGE_BUILD_NUMBER"),
                    osVersion = str("HINGE_OS_VERSION"),
                    devicePlatform = str("HINGE_DEVICE_PLATFORM"),
                    deviceModelCode = str("HINGE_DEVICE_MODEL_CODE"),
                    deviceRegion = str("HINGE_DEVICE_REGION"),
                    acceptLanguage = str("HINGE_ACCEPT_LANGUAGE"),
                    sendbirdWsOrigin = str("HINGE_SENDBIRD_WS_ORIGIN"),
                ),
            )
        }
    }
}

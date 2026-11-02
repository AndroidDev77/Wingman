plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.wingman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wingman"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // WING-025: instrumented E2E tests (src/androidTest), run on the CI emulator against a live backend.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulator loopback to the host's local backend. Overridden per build type later.
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000/\"")
        // Offline mock API by default so the app runs with no backend. Build with
        // `-Pwingman.useMockApi=false` to talk to API_BASE_URL.
        val useMock = (project.findProperty("wingman.useMockApi") as String?)?.toBooleanStrictOrNull() ?: true
        buildConfigField("boolean", "USE_MOCK_API", useMock.toString())

        // I-011 OIDC sign-in (AppAuth, authorization code + PKCE). The IdP is Auth0 + Google (I-014).
        // These default to empty (real sign-in disabled) so dev builds and CI keep the dev-token path; the beta
        // tenant's committed values are passed only by scripts/release/build.sh (docs/auth0.md). Set them with
        // -Pwingman.oidc.issuer=... etc. or in ~/.gradle/gradle.properties (docs/android_auth.md).
        fun prop(name: String, default: String = "") = (project.findProperty(name) as String?)?.trim() ?: default
        fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val oidcRedirect = prop("wingman.oidc.redirectUri")
        buildConfigField("String", "OIDC_ISSUER", quoted(prop("wingman.oidc.issuer")))
        buildConfigField("String", "OIDC_CLIENT_ID", quoted(prop("wingman.oidc.clientId")))
        buildConfigField("String", "OIDC_REDIRECT_URI", quoted(oidcRedirect))
        buildConfigField("String", "OIDC_SCOPES", quoted(prop("wingman.oidc.scopes", "openid profile email offline_access")))
        buildConfigField("String", "OIDC_AUDIENCE", quoted(prop("wingman.oidc.audience")))
        // Optional Auth0 `connection` (e.g. google-oauth2) to skip the Universal Login picker (docs/auth0.md).
        buildConfigField("String", "OIDC_CONNECTION", quoted(prop("wingman.oidc.connection")))
        // AppAuth's RedirectUriReceiverActivity (from the library manifest) handles this scheme.
        // Unconfigured builds use an inert app-specific placeholder.
        manifestPlaceholders["appAuthRedirectScheme"] =
            oidcRedirect.substringBefore(':', missingDelimiterValue = "").ifEmpty { "com.wingman.auth" }

        // WING-029 (D-021, D-023, D-026): the experimental Hinge connector is inert unless BOTH the
        // flag and the client identity are set. Default OFF (CI and every normal build). Never a Play
        // build: the guard at the end of this file fails any app-bundle task when the flag is on.
        val experimental = (project.findProperty("wingman.experimental") as String?)?.toBooleanStrictOrNull() ?: false
        buildConfigField("boolean", "WINGMAN_EXPERIMENTAL", experimental.toString())
        // Client identity documented by f0rr0/hinge-rs@cffe397 (src/settings.rs, src/client/transport.rs,
        // src/client/sendbird.rs). The values live in ~/.gradle/gradle.properties of the person building
        // the experimental APK, never in this repo (docs/integrations/hinge.md section 3a). Empty in
        // every non-experimental build, whatever properties are set.
        fun hinge(name: String) = quoted(if (experimental) prop("wingman.hinge.$name") else "")
        buildConfigField("String", "HINGE_BASE_URL", hinge("baseUrl"))
        buildConfigField("String", "HINGE_SENDBIRD_API_URL", hinge("sendbirdApiUrl"))
        buildConfigField("String", "HINGE_SENDBIRD_WS_URL", hinge("sendbirdWsUrl"))
        buildConfigField("String", "HINGE_SENDBIRD_WS_ORIGIN", hinge("sendbirdWsOrigin"))
        buildConfigField("String", "HINGE_SENDBIRD_APP_ID", hinge("sendbirdAppId"))
        buildConfigField("String", "HINGE_SENDBIRD_SDK_VERSION", hinge("sendbirdSdkVersion"))
        buildConfigField("String", "HINGE_APP_VERSION", hinge("appVersion"))
        buildConfigField("String", "HINGE_BUILD_NUMBER", hinge("buildNumber"))
        buildConfigField("String", "HINGE_OS_VERSION", hinge("osVersion"))
        buildConfigField("String", "HINGE_DEVICE_PLATFORM", hinge("devicePlatform"))
        buildConfigField("String", "HINGE_DEVICE_MODEL_CODE", hinge("deviceModelCode"))
        buildConfigField("String", "HINGE_DEVICE_REGION", hinge("deviceRegion"))
        buildConfigField("String", "HINGE_ACCEPT_LANGUAGE", hinge("acceptLanguage"))
        buildConfigField("String", "HINGE_USER_AGENT", hinge("userAgent"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    testOptions {
        unitTests.all {
            // Contract example tests read ../../contracts from the repo.
            it.systemProperty("wingman.contractsDir", rootProject.file("../contracts").absolutePath)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.appauth)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // WING-025: instrumented E2E tests on the emulator (.github/workflows/e2e.yml).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

// I-011: the release source set replaces the debug-only dev-token sign-in with a stub. CI builds
// only the debug variant, so compile the release Kotlin too whenever unit tests run: a release
// build that fails to compile (or still references the debug panel) fails the Android job.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn("compileReleaseKotlin")
}

// D-023 / D-026: an experimental build must never become a Play bundle. Fails the build when
// `-Pwingman.experimental=true` is combined with an Android App Bundle task (`bundle<BuildType>`,
// `package<BuildType>Bundle`, `sign<BuildType>Bundle`). Matched by exact name: AGP's internal
// `bundle<BuildType>ClassesTo*Jar` tasks (used by unit tests) are not app bundles and stay allowed.
gradle.taskGraph.whenReady {
    val experimentalBuild = (project.findProperty("wingman.experimental") as String?)?.toBooleanStrictOrNull() ?: false
    val buildTypes = android.buildTypes.names.map { it.replaceFirstChar(Char::uppercaseChar) } + listOf("Debug", "Release")
    val appBundleTasks = buildTypes.flatMap { listOf("bundle$it", "package${it}Bundle", "sign${it}Bundle") }.toSet()
    val requested = allTasks.map { it.name }.filter { it in appBundleTasks }
    if (experimentalBuild && requested.isNotEmpty()) {
        throw GradleException(
            "wingman.experimental=true cannot build an app bundle ($requested). " +
                "D-023: the experimental Hinge connector is sideload/internal only, never a Play build.",
        )
    }
}

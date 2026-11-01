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

        // I-011 OIDC sign-in (AppAuth, authorization code + PKCE). No IdP is chosen yet (I-014), so
        // these default to empty and real sign-in is disabled. Set them with
        // -Pwingman.oidc.issuer=... etc. or in ~/.gradle/gradle.properties (docs/android_auth.md).
        fun prop(name: String, default: String = "") = (project.findProperty(name) as String?)?.trim() ?: default
        fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val oidcRedirect = prop("wingman.oidc.redirectUri")
        buildConfigField("String", "OIDC_ISSUER", quoted(prop("wingman.oidc.issuer")))
        buildConfigField("String", "OIDC_CLIENT_ID", quoted(prop("wingman.oidc.clientId")))
        buildConfigField("String", "OIDC_REDIRECT_URI", quoted(oidcRedirect))
        buildConfigField("String", "OIDC_SCOPES", quoted(prop("wingman.oidc.scopes", "openid profile email offline_access")))
        buildConfigField("String", "OIDC_AUDIENCE", quoted(prop("wingman.oidc.audience")))
        // AppAuth's RedirectUriReceiverActivity (from the library manifest) handles this scheme.
        // Unconfigured builds use an inert app-specific placeholder.
        manifestPlaceholders["appAuthRedirectScheme"] =
            oidcRedirect.substringBefore(':', missingDelimiterValue = "").ifEmpty { "com.wingman.auth" }
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

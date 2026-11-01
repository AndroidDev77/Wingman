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
        // Emulator loopback to the host's local backend. Overridden per build type later.
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000/\"")
        // Offline mock API by default so the app runs with no backend. Build with
        // `-Pwingman.useMockApi=false` to talk to API_BASE_URL.
        val useMock = (project.findProperty("wingman.useMockApi") as String?)?.toBooleanStrictOrNull() ?: true
        buildConfigField("boolean", "USE_MOCK_API", useMock.toString())
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
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

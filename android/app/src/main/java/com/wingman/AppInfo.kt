package com.wingman

/** Pure-Kotlin helpers kept free of Android types so they run in JVM unit tests. */
object AppInfo {
    const val NAME = "Wingman"

    fun greeting(): String = "$NAME is set up"

    /** Normalizes a backend base URL so Retrofit/Ktor always see exactly one trailing slash. */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Base URL must start with http:// or https://"
        }
        return trimmed.trimEnd('/') + "/"
    }
}

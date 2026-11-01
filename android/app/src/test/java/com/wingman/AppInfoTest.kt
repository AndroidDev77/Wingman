package com.wingman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppInfoTest {
    @Test
    fun greetingNamesTheApp() {
        assertEquals("Wingman is set up", AppInfo.greeting())
    }

    @Test
    fun baseUrlGetsExactlyOneTrailingSlash() {
        assertEquals("http://10.0.2.2:8000/", AppInfo.normalizeBaseUrl("http://10.0.2.2:8000"))
        assertEquals("https://api.example.com/", AppInfo.normalizeBaseUrl(" https://api.example.com/// "))
    }

    @Test
    fun baseUrlWithoutSchemeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AppInfo.normalizeBaseUrl("10.0.2.2:8000") }
    }
}

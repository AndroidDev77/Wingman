package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AutomationMode
import com.wingman.core.model.AutomationSettings
import com.wingman.core.model.ContactPreferenceOverrideUpdate
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.Intent
import com.wingman.core.model.IntentMode
import com.wingman.core.model.PreferencesUpdate
import com.wingman.core.model.StyleProfile
import com.wingman.core.model.Tone
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** WING-013 additions to the API client: contact override PUT/DELETE and version history, per contract v1.0.0. */
class PreferenceEndpointsTest {
    private lateinit var server: MockWebServer
    private lateinit var http: HttpWingmanApi
    private val contact = "0192f3a0-0000-7000-8000-0000000000c1"

    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        http = HttpWingmanApi(server.url("/").toString(), okHttpClient = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun putPreferencesSendsQuotedIfMatch() = runTest {
        server.enqueue(json(example("preferences.response.json")))
        http.putPreferences(PreferencesUpdate(Intent(IntentMode.OPEN), StyleProfile(), AutomationSettings(AutomationMode.MANUAL)), ifMatchVersion = 3)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("\"3\"", req.getHeader("If-Match"))
    }

    @Test fun putContactPreferencesSendsIfMatchAndBody() = runTest {
        server.enqueue(json(example("contact_preferences.response.json")))
        val body = ContactPreferenceOverrideUpdate(intent = Intent(IntentMode.CUSTOM, "Keep it light"), automationMode = AutomationMode.MANUAL)
        val r = http.putContactPreferences(contact, body, ifMatchVersion = 0)
        assertEquals(1, r.getOrNull()!!.override.version)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v1/contacts/$contact/preferences", req.path)
        assertEquals("\"0\"", req.getHeader("If-Match"))
        val sent = req.body.readUtf8()
        assertTrue(sent, "\"custom_description\":\"Keep it light\"" in sent && "\"automation_mode\":\"manual\"" in sent)
    }

    @Test fun putContactPreferencesWithoutIfMatchOmitsHeaderAnd412Maps() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(412).setHeader("Content-Type", "application/problem+json")
                .setBody("""{"type":"https://wingman.invalid/problems/precondition_failed","title":"Precondition failed","status":412,"code":"precondition_failed"}"""),
        )
        val r = http.putContactPreferences(contact, ContactPreferenceOverrideUpdate())
        assertEquals(ErrorCode.PRECONDITION_FAILED, r.errorOrNull()!!.code)
        assertNull(server.takeRequest().getHeader("If-Match"))
    }

    @Test fun deleteContactPreferencesHandles204() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ApiResult.Success(Unit), http.deleteContactPreferences(contact))
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/contacts/$contact/preferences", req.path)

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ErrorCode.NOT_FOUND, http.deleteContactPreferences(contact).errorOrNull()!!.code)
    }

    @Test fun listPreferenceVersionsSendsCursorAndLimit() = runTest {
        val page = """{"items":[${example("preferences.response.json")}],"next_cursor":null}"""
        server.enqueue(json(page))
        val r = http.listPreferenceVersions(PageRequest("abc", 10))
        assertEquals(1, r.getOrNull()!!.items.size)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/preferences/versions", url.encodedPath)
        assertEquals("abc", url.queryParameter("cursor"))
        assertEquals("10", url.queryParameter("limit"))
    }

    @Test fun mockContactOverrideLifecycle() = runTest {
        val api = MockWingmanApi()
        val c = MockFixtures.CONTACT_SAM
        val user = api.putPreferences(PreferencesUpdate(Intent(IntentMode.SERIOUS), StyleProfile(tone = Tone.WARM), AutomationSettings(AutomationMode.MANUAL)), 0).getOrNull()!!

        val saved = api.putContactPreferences(c, ContactPreferenceOverrideUpdate(intent = Intent(IntentMode.OPEN)), 0).getOrNull()!!
        assertEquals(1, saved.override.version)
        assertEquals(IntentMode.OPEN, saved.effective.intent.mode)
        assertEquals(Tone.WARM, saved.effective.style.tone)
        assertEquals(user, api.getPreferences().getOrNull()) // user-wide untouched

        assertEquals(ErrorCode.PRECONDITION_FAILED, api.putContactPreferences(c, ContactPreferenceOverrideUpdate(), 0).errorOrNull()!!.code)
        assertEquals(ErrorCode.VALIDATION_ERROR, api.putContactPreferences(c, ContactPreferenceOverrideUpdate(intent = Intent(IntentMode.CUSTOM)), 1).errorOrNull()!!.code)
        assertEquals(ErrorCode.VALIDATION_ERROR, api.putContactPreferences(c, ContactPreferenceOverrideUpdate(intent = Intent(IntentMode.OPEN, "x")), 1).errorOrNull()!!.code)

        assertTrue(api.deleteContactPreferences(c) is ApiResult.Success)
        val cleared = api.getContactPreferences(c).getOrNull()!!
        assertEquals(2, cleared.override.version) // clearing appends an all-null version, like the backend
        assertNull(cleared.override.intent)
        assertEquals(IntentMode.SERIOUS, cleared.effective.intent.mode)
        api.deleteContactPreferences(c)
        assertEquals(2, api.getContactPreferences(c).getOrNull()!!.override.version) // no-op when already empty

        assertEquals(ErrorCode.NOT_FOUND, api.deleteContactPreferences("0192f3a0-0000-7000-8000-00000000ffff").errorOrNull()!!.code)
        assertEquals(ErrorCode.NOT_FOUND, api.putContactPreferences("0192f3a0-0000-7000-8000-00000000ffff", ContactPreferenceOverrideUpdate()).errorOrNull()!!.code)
    }

    @Test fun mockVersionsAreNewestFirstAndCustomNeedsDescription() = runTest {
        val api = MockWingmanApi()
        assertTrue(api.listPreferenceVersions().getOrNull()!!.items.isEmpty())
        val u = PreferencesUpdate(Intent(IntentMode.OPEN), StyleProfile(), AutomationSettings(AutomationMode.MANUAL))
        api.putPreferences(u, 0)
        api.putPreferences(u.copy(intent = Intent(IntentMode.SERIOUS)), 1)
        assertEquals(listOf(2, 1), api.listPreferenceVersions().getOrNull()!!.items.map { it.version })
        assertEquals(ErrorCode.VALIDATION_ERROR, api.putPreferences(u.copy(intent = Intent(IntentMode.CUSTOM, "")), 2).errorOrNull()!!.code)
    }
}

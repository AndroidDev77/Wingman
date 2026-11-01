package com.wingman.e2e

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * WING-025. Test-side HTTP helper for the instrumented E2E tests. It plays the OTHER side of the
 * conversation (a contact's inbound message, via ingestConnectorEvent on a `mock` connection) and
 * reads server state to assert on. Every USER action in the tests goes through the app's UI.
 *
 * Arguments (adb shell am instrument -e ...):
 *   wingman.token        dev-stub JWT minted by the backend CLI (app.security.dev_stub)
 *   wingman.baseUrl      default http://10.0.2.2:8000/ (the emulator's alias for the host)
 *   wingman.runId        unique per CI run, used in contact names
 */
object E2eArgs {
    private val args get() = InstrumentationRegistry.getArguments()
    val token: String get() = args.getString("wingman.token") ?: error("instrumentation argument wingman.token is required")
    val baseUrl: String get() = (args.getString("wingman.baseUrl") ?: "http://10.0.2.2:8000/").trimEnd('/') + "/"
    val runId: String get() = args.getString("wingman.runId") ?: UUID.randomUUID().toString().take(8)
}

class E2eBackend(private val baseUrl: String = E2eArgs.baseUrl, private val token: String = E2eArgs.token) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    data class Reply(val code: Int, val body: String) {
        fun obj() = JSONObject(body)
    }

    fun call(method: String, path: String, body: JSONObject? = null, key: String? = null): Reply {
        val b = Request.Builder().url(baseUrl + path.trimStart('/')).header("Authorization", "Bearer $token")
        key?.let { b.header("Idempotency-Key", it) }
        b.method(method, body?.toString()?.toRequestBody(json) ?: if (method == "POST" || method == "PUT") "{}".toRequestBody(json) else null)
        http.newCall(b.build()).execute().use { r -> return Reply(r.code, r.body?.string().orEmpty()) }
    }

    private fun ok(r: Reply, vararg codes: Int): Reply {
        check(r.code in codes.toSet()) { "HTTP ${r.code}: ${r.body}" }
        return r
    }

    fun me(): JSONObject = ok(call("GET", "v1/me"), 200).obj()

    fun preferences(): JSONObject = ok(call("GET", "v1/preferences"), 200).obj()

    /** Saves assisted-mode preferences (used by the SMS test, which does not test onboarding). */
    fun putAssisted(): JSONObject = ok(
        call(
            "PUT",
            "v1/preferences",
            JSONObject()
                .put("intent", JSONObject().put("mode", "open").put("custom_description", JSONObject.NULL))
                .put("style", JSONObject())
                .put("automation", JSONObject().put("mode", "assisted")),
        ),
        200,
    ).obj()

    fun pairMock(name: String): JSONObject = ok(
        call("POST", "v1/connections", JSONObject().put("platform", "mock").put("display_name", name), UUID.randomUUID().toString()),
        201,
    ).obj()

    /** The contact sends a message: one `mock.inbound_message_injected` envelope. */
    fun injectInbound(connection: JSONObject, userId: String, contact: String, text: String): JSONObject {
        val now = Instant.now().toString()
        val eventId = UUID.randomUUID().toString()
        val event = JSONObject()
            .put("schema_version", "connector_event.v1")
            .put("event_id", eventId)
            .put("type", "mock.inbound_message_injected")
            .put("platform", "mock")
            .put("connection_id", connection.getString("id"))
            .put("user_id", userId)
            .put("external_message_id", "android-e2e-$eventId")
            .put("external_thread_id", "android-e2e-thread-$contact")
            .put("occurred_at", now)
            .put("observed_at", now)
            .put(
                "payload",
                JSONObject()
                    .put("sender", JSONObject().put("external_contact_id", contact).put("display_name", contact))
                    .put("text", text)
                    .put("mock", JSONObject().put("scenario_id", "wing025_android").put("sequence", 1)),
            )
        return ok(call("POST", "v1/connections/${connection.getString("id")}/events", event, eventId), 202).obj()
    }

    fun conversations(): JSONArray = ok(call("GET", "v1/conversations?limit=100"), 200).obj().getJSONArray("items")

    fun conversationFor(contact: String): JSONObject? =
        conversations().objects().firstOrNull { it.getJSONObject("contact").getString("display_name") == contact }

    fun messages(conversationId: String): List<JSONObject> =
        ok(call("GET", "v1/conversations/$conversationId/messages?limit=100"), 200).obj().getJSONArray("items").objects()

    fun approvals(status: String, conversationId: String): List<JSONObject> =
        ok(call("GET", "v1/approvals?status=$status&conversation_id=$conversationId"), 200).obj().getJSONArray("items").objects()

    fun actions(conversationId: String? = null, contactId: String? = null): List<JSONObject> {
        val q = buildList {
            add("limit=100")
            conversationId?.let { add("conversation_id=$it") }
            contactId?.let { add("contact_id=$it") }
        }.joinToString("&")
        return ok(call("GET", "v1/agent-actions?$q"), 200).obj().getJSONArray("items").objects()
    }

    fun contactAutomation(contactId: String): JSONObject = ok(call("GET", "v1/contacts/$contactId/automation"), 200).obj()

    fun connections(): List<JSONObject> = ok(call("GET", "v1/connections?limit=100"), 200).obj().getJSONArray("items").objects()

    companion object {
        fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}

/** Polls [probe] until it returns non-null (bounded; for server-side state the UI does not drive). */
fun <T : Any> eventually(timeoutMs: Long = 30_000, intervalMs: Long = 500, what: String, probe: () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            probe()?.let { return it }
        } catch (e: Exception) {
            last = e
        }
        Thread.sleep(intervalMs)
    }
    throw AssertionError("timed out after ${timeoutMs}ms waiting for $what", last)
}

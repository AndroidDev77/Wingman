package com.wingman.integrations.hinge

import com.wingman.integrations.sms.InMemorySmsStateStore
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The production socket path ([OkHttpChatSocketFactory] over OkHttp) against a fake Sendbird
 * WebSocket on MockWebServer: handshake headers, the gate on the upgrade request, LOGI, and the
 * echo-confirmed MESG. No real Sendbird server is contacted.
 */
class HingeOkHttpSocketTest {
    private val server = MockWebServer()
    private val received = CopyOnWriteArrayList<String>()
    private var echo = true

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("LOGI{\"key\":\"${Sentinels.SESSION_KEY}\",\"ping_interval\":15}")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    if (text.startsWith("MESG") && echo) {
                        val reqId = Regex("\"req_id\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                        webSocket.send("MESG{\"msg_id\":5150,\"req_id\":\"$reqId\",\"created_at\":$T0}")
                    }
                }
            })
        }
        server.start()
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun identity() = HingeClientIdentity(
        hingeBaseUrl = server.url("/hinge/").toString(), sendbirdApiUrl = server.url("/sb/").toString(),
        sendbirdWsUrl = server.url("/ws/").toString().replaceFirst("http://", "ws://"),
        sendbirdAppId = "TEST-APP-ID", sendbirdSdkVersion = "4.0.0", appVersion = "10.0.0", buildNumber = "1", osVersion = "26.0",
        devicePlatform = "iOS", deviceModelCode = "Model1,1", deviceRegion = "US", acceptLanguage = "en-US",
    )

    private fun transport(optedIn: () -> Boolean = { true }): Pair<SendbirdChatTransport, HingeNetGate> {
        val store = InMemorySmsStateStore()
        val clock = FakeClock()
        val gate = HingeNetGate(HingeBuildFlags(true, identity()), optedIn, HingeCircuitBreaker(store, clock), HingeRateLimiter(store, HingeLimits.DEFAULT, clock, FakeSleeper(clock)))
        val http = HingeHttp(gate, identity(), OkHttpClient(), clock)
        return SendbirdChatTransport(http, gate, identity(), OkHttpChatSocketFactory(http.webSocketClient()), clock, loginTimeoutMillis = 5_000) to gate
    }

    private val session = HingeSession.newInstall().copy(
        identityId = Sentinels.SELF_ID, hingeToken = Sentinels.HINGE_TOKEN, hingeExpiresAtMillis = Long.MAX_VALUE,
        chatToken = Sentinels.CHAT_TOKEN, chatExpiresAtMillis = Long.MAX_VALUE,
    )

    @Test fun `handshake, LOGI and an echo-confirmed send over a real OkHttp socket`() {
        val (t, gate) = transport()
        assertEquals(SendbirdConnect.Ready, t.connect(session))
        val upgrade = server.takeRequest()
        assertEquals(Sentinels.CHAT_TOKEN, upgrade.getHeader("SENDBIRD-WS-TOKEN"))
        assertEquals(Sentinels.SELF_ID, upgrade.requestUrl!!.queryParameter("user_id"))
        assertEquals("TEST-APP-ID", upgrade.requestUrl!!.queryParameter("ai"))
        assertTrue(upgrade.requestUrl!!.encodedPath == "/ws/")
        val r = t.send(Sentinels.CHANNEL, Sentinels.TEXT, 5_000)
        assertEquals(SendbirdSend.Acknowledged("5150", T0), r)
        // The frame survived quotes, newline and emoji, and is newline-terminated.
        val frame = received.single { it.startsWith("MESG") }
        assertTrue(frame.endsWith("\n"))
        assertTrue(frame.contains("\\\"quotes\\\"") && frame.contains("\\n"))
        t.close()
        assertEquals(1, gate.admittedCalls.get()) // the socket open is counted once
    }

    @Test fun `no echo is a timeout (outcome unknown), never a resend`() {
        echo = false
        val (t, _) = transport()
        t.connect(session)
        assertEquals(SendbirdSend.Timeout, t.send(Sentinels.CHANNEL, "x", 300))
        Thread.sleep(100)
        assertEquals(1, received.count { it.startsWith("MESG") })
        t.close()
    }

    @Test fun `the socket is not opened when the user opted out`() {
        val (t, _) = transport(optedIn = { false })
        val e = assertThrows(HingeCallRefused::class.java) { t.connect(session) }
        assertEquals(HingeCallRefused.NOT_OPTED_IN, e.code)
        assertEquals(0, server.requestCount)
    }
}

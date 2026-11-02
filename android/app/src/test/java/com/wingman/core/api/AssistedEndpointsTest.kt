package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.model.AssistedDeliveryConfirmRequest
import com.wingman.core.model.AssistedDeliveryStatus
import com.wingman.core.model.ConnectionCreateRequest
import com.wingman.core.model.ConnectionTransport
import com.wingman.core.model.ConnectorCapabilities
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.DeliveryState
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.InboundEventKind
import com.wingman.core.model.MessageOrigin
import com.wingman.core.model.RejectRequest
import com.wingman.core.model.SenderIdentity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Contract 1.3.0 assisted-delivery and inbound-notice operations (WING-027/028) over MockWebServer. */
class AssistedEndpointsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpWingmanApi

    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val id = "0192f3a0-0000-7000-8000-000000000a01"
    private val key = IdempotencyKey("0192f3a0-0000-4000-8000-0000000003a1")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = HttpWingmanApi(server.url("/").toString(), tokenProvider = { "test-jwt" }, okHttpClient = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun listAssistedDeliveriesSendsFiltersAndDecodesTheExample() = runTest {
        server.enqueue(json(example("assisted_deliveries.page.json")))
        val page = api.listAssistedDeliveries(
            PageRequest(limit = 20),
            AssistedDeliveryFilter(status = AssistedDeliveryStatus.PENDING, conversationId = "0192f3a0-0000-7000-8000-0000000000b5"),
        ).getOrNull()!!
        val d = page.items.single()
        assertEquals(AssistedDeliveryStatus.PENDING, d.status)
        assertEquals("tinder", d.platform)
        assertEquals("tinder-match-12", d.externalThreadId)
        assertNull(d.messageId)
        assertTrue(!d.toString().contains("Saturday")) // text redacted
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/assisted-deliveries?limit=20&status=pending&conversation_id=0192f3a0-0000-7000-8000-0000000000b5", req.path)
        assertEquals("Bearer test-jwt", req.getHeader("Authorization"))
    }

    @Test fun confirmPostsBodyAndKeyAndDecodesUserReportedSent() = runTest {
        server.enqueue(json(example("assisted_delivery.confirm.response.json")).setHeader("Idempotent-Replayed", "true"))
        val body = Json.decodeFromString(AssistedDeliveryConfirmRequest.serializer(), example("assisted_delivery.confirm.request.json"))
        val r = api.confirmAssistedDelivery(id, body, key)
        val v = (r as ApiResult.Success).value
        assertTrue(r.idempotentReplayed)
        assertEquals(AssistedDeliveryStatus.USER_REPORTED_SENT, v.assistedDelivery.status)
        assertEquals(MessageOrigin.ASSISTED_DRAFT, v.message!!.origin)
        assertEquals(DeliveryState.USER_REPORTED_SENT, v.message!!.deliveryState)
        assertNull(v.message!!.sentAt)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/assisted-deliveries/$id/confirm", req.path)
        assertEquals(key.value, req.getHeader("Idempotency-Key"))
        assertEquals(Json.parseToJsonElement(example("assisted_delivery.confirm.request.json")), Json.parseToJsonElement(req.body.readUtf8()))
    }

    @Test fun confirmWithoutAnEditSendsAnEmptyObject() = runTest {
        server.enqueue(json(example("assisted_delivery.confirm.response.json")))
        api.confirmAssistedDelivery(id, AssistedDeliveryConfirmRequest(), key)
        assertEquals("{}", server.takeRequest().body.readUtf8())
    }

    @Test fun conflictIsATyped409() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/problem+json").setBody(example("problem.assisted_delivery_not_pending.json")))
        val e = api.confirmAssistedDelivery(id, AssistedDeliveryConfirmRequest(), key).errorOrNull() as ApiError.Http
        assertEquals(409, e.status)
        assertEquals(ErrorCode.ASSISTED_DELIVERY_NOT_PENDING, e.code)
        assertTrue(!e.isRetryable)
    }

    @Test fun dismissPostsAndDecodesANullMessage() = runTest {
        val dismissed = Json.parseToJsonElement(example("assisted_deliveries.page.json")).jsonObject["items"]!!.toString()
            .removePrefix("[").removeSuffix("]")
            .replace("\"status\": \"pending\"", "\"status\": \"dismissed\"")
            .replace("\"status\":\"pending\"", "\"status\":\"dismissed\"")
            .replace("\"decided_at\":null", "\"decided_at\":\"2026-11-02T11:07:00Z\"")
        server.enqueue(json("""{"assisted_delivery": $dismissed, "message": null}"""))
        val v = api.dismissAssistedDelivery(id, RejectRequest(), key).getOrNull()!!
        assertEquals(AssistedDeliveryStatus.DISMISSED, v.assistedDelivery.status)
        assertNull(v.message)
        val req = server.takeRequest()
        assertEquals("/v1/assisted-deliveries/$id/dismiss", req.path)
        assertEquals(key.value, req.getHeader("Idempotency-Key"))
    }

    @Test fun listInboundNoticesDecodesTheExample() = runTest {
        server.enqueue(json(example("inbound_notices.page.json")))
        val n = api.listInboundNotices(PageRequest(limit = 5), connectionId = "0192f3a0-0000-7000-8000-0000000000a6").getOrNull()!!.items.single()
        assertEquals(InboundEventKind.LIKE_COMMENT, n.kind)
        assertEquals(SenderIdentity.UNLINKED, n.identity)
        assertEquals(ConnectionTransport.NOTIFICATION, n.source)
        assertNull(n.externalConversationId)
        assertTrue(!n.toString().contains("Jordan"))
        assertEquals("/v1/inbound-notices?limit=5&connection_id=0192f3a0-0000-7000-8000-0000000000a6", server.takeRequest().path)
    }

    @Test fun networkFailureIsRetryable() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = api.listAssistedDeliveries().errorOrNull()!!
        assertTrue(e is ApiError.Network && e.isRetryable)
    }

    @Test fun tinderNotificationPairingBodyMatchesTheContractExample() = runTest {
        val example = Json.parseToJsonElement(example("connection.create.tinder_notification.request.json")).jsonObject
        val caps = WingmanJson.decodeFromJsonElement(ConnectorCapabilities.serializer(), example["capabilities"]!!)
        val body = ConnectionCreateRequest("tinder", "Tinder notifications", "0192f3a0-0000-4000-8000-0000000000aa", caps, ConnectionTransport.NOTIFICATION)
        server.enqueue(json(example("connection.create.sms.response.json"), 201))
        api.createConnection(body, key)
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(example, sent)
    }
}

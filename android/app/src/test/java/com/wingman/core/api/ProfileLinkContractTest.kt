package com.wingman.core.api

import com.wingman.core.api.http.HttpWingmanApi
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.AgentActionType
import com.wingman.core.model.ContractFiles
import com.wingman.core.model.ConversationProfileLink
import com.wingman.core.model.ConversationProfileLinkRequest
import com.wingman.core.model.Draft
import com.wingman.core.model.DraftCreateRequest
import com.wingman.core.model.ErrorCode
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfileLinkOrigin
import com.wingman.core.model.ProfilePrompt
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ProfileSource
import com.wingman.core.model.Verification
import com.wingman.integrations.hinge.notifications.HingeNotificationPlugin
import com.wingman.integrations.notifications.CapabilityState
import com.wingman.integrations.notifications.CapabilityStatus
import com.wingman.integrations.notifications.InMemoryNotificationStateStore
import com.wingman.integrations.notifications.LiveReplyActions
import com.wingman.integrations.notifications.NFakeClock
import com.wingman.integrations.notifications.NotificationListenerCore
import com.wingman.integrations.notifications.NotificationPluginRegistry
import com.wingman.integrations.notifications.NotificationReplyAdapter
import com.wingman.integrations.notifications.NotificationSyncHost
import com.wingman.integrations.notifications.NotificationToggleResult
import com.wingman.integrations.notifications.ProfileReadCapability
import com.wingman.integrations.notifications.ProfileReadCapabilitySource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Contract 1.3.2 (D-032) on the client: the profile-link and delete operations over HTTP, the
 * examples decoding into the models, the offline mock mirroring the server's rules, and the shared
 * `profile_read` capability hook. Synthetic data only.
 */
class ProfileLinkContractTest {
    private fun example(name: String) = File(ContractFiles.examplesDir(), name).readText()
    private val conversationId = "0192f3a0-0000-7000-8000-0000000000b1"
    private val profileId = "0192f3a0-0000-7000-8000-000000000a01"

    @After fun clearHook() {
        ProfileReadCapability.source = null
    }

    @Test fun examplesDecodeIntoTheModels() {
        val link = WingmanJson.decodeFromString(ConversationProfileLink.serializer(), example("profile_link.response.json"))
        assertEquals(ProfileLinkOrigin.USER, link.origin)
        val byUser = WingmanJson.decodeFromString(ConversationProfileLinkRequest.serializer(), example("profile_link.put.request.json"))
        assertEquals(ProfileLinkOrigin.USER, byUser.origin)
        // `origin: user` is the contract default and is not sent.
        assertEquals(Json.parseToJsonElement(example("profile_link.put.request.json")), WingmanJson.encodeToJsonElement(ConversationProfileLinkRequest.serializer(), byUser))
        val byId = WingmanJson.decodeFromString(ConversationProfileLinkRequest.serializer(), example("profile_link.put.device_match.request.json"))
        assertEquals(Json.parseToJsonElement(example("profile_link.put.device_match.request.json")), WingmanJson.encodeToJsonElement(ConversationProfileLinkRequest.serializer(), byId))
        val draft = WingmanJson.decodeFromString(Draft.serializer(), example("draft.profile_grounded.response.json"))
        assertEquals(profileId, draft.grounding.profileId)
        assertEquals(listOf("prompts/1"), draft.grounding.profileFields)
        // A 1.3.1 draft (no profile keys) still decodes.
        val old = WingmanJson.decodeFromString(Draft.serializer(), example("draft.response.json"))
        assertNull(old.grounding.profileId)
        assertTrue(old.grounding.profileFields.isEmpty())
        val device = WingmanJson.decodeFromString(ProfileImportRequest.serializer(), example("profile.import.device_screen.request.json"))
        assertEquals(ProfileSource.DEVICE, device.source)
        assertFalse("private fields stay out of logs", device.toString().contains("fish"))
    }

    @Test fun httpOperationsUseTheContractPathsAndMethods() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val api = HttpWingmanApi(server.url("/").toString(), tokenProvider = { "test-jwt" })
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(example("profile_link.response.json")))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(
                MockResponse().setResponseCode(404).setHeader("Content-Type", "application/problem+json")
                    .setBody("""{"type":"https://wingman.invalid/problems/not_found","title":"x","status":404,"code":"not_found"}"""),
            )

            val linked = api.putConversationProfileLink(conversationId, ConversationProfileLinkRequest(profileId, ProfileLinkOrigin.DEVICE_MATCH)) as ApiResult.Success
            assertEquals(profileId, linked.value.profileId)
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("/v1/conversations/$conversationId/profile-link", put.path)
            assertNull("PUT needs no idempotency key", put.getHeader("Idempotency-Key"))
            assertEquals("device_match", Json.parseToJsonElement(put.body.readUtf8()).jsonObject["origin"]!!.jsonPrimitive.content)

            assertTrue(api.deleteConversationProfileLink(conversationId) is ApiResult.Success)
            val unlink = server.takeRequest()
            assertEquals("DELETE /v1/conversations/$conversationId/profile-link", unlink.method + " " + unlink.path)

            assertTrue(api.deleteProfile(profileId) is ApiResult.Success)
            val delete = server.takeRequest()
            assertEquals("DELETE /v1/profiles/$profileId", delete.method + " " + delete.path)

            assertEquals(ErrorCode.NOT_FOUND, api.deleteProfile(profileId).errorOrNull()!!.code)
        } finally {
            server.shutdown()
        }
    }

    private fun manual(externalId: String, provider: ProfileProvider = ProfileProvider.HINGE) = ProfileImportRequest(
        provider = provider, externalProfileId = externalId, source = ProfileSource.MANUAL, displayName = "Synthetic Riley",
        prompts = listOf(ProfilePrompt("Typical Sunday", "Synthetic farmers market, then a trail run.")),
        sourceObservedAt = Instant.parse("2026-11-01T10:00:00Z"),
    )

    @Test fun mockMirrorsLinkingUnlinkingDeletingAndTheDraft() = runTest {
        val api = MockWingmanApi()
        val conv = MockFixtures.CONVERSATION_SAM
        val profile = api.importProfile(manual("screen:synthetic-1"), IdempotencyKey.random()).getOrNull()!!
        assertTrue(api.listProfileReviewQueue().getOrNull()!!.items.any { it.id == profile.id })

        // A display name is not an identity: device_match without the same stable ID is refused.
        val refused = api.putConversationProfileLink(conv, ConversationProfileLinkRequest(profile.id, ProfileLinkOrigin.DEVICE_MATCH))
        assertEquals(ErrorCode.VALIDATION_ERROR, refused.errorOrNull()!!.code)
        assertEquals(ErrorCode.VALIDATION_ERROR, api.putConversationProfileLink(conv, ConversationProfileLinkRequest("0192f3a0-0000-7000-8000-00000000ffff")).errorOrNull()!!.code)
        assertEquals(ErrorCode.NOT_FOUND, api.putConversationProfileLink("0192f3a0-0000-7000-8000-00000000fffe", ConversationProfileLinkRequest(profile.id)).errorOrNull()!!.code)

        val link = api.putConversationProfileLink(conv, ConversationProfileLinkRequest(profile.id)).getOrNull()!!
        assertEquals(ProfileLinkOrigin.USER, link.origin)
        assertEquals(link, api.putConversationProfileLink(conv, ConversationProfileLinkRequest(profile.id)).getOrNull())
        // A linked profile is a match's profile, not a discovery candidate.
        assertFalse(api.listProfileReviewQueue().getOrNull()!!.items.any { it.id == profile.id })

        val draft = api.createDraft(conv, DraftCreateRequest(), IdempotencyKey.random()).getOrNull()!!
        assertEquals(profile.id, draft.grounding.profileId)
        assertEquals(listOf("prompts/0"), draft.grounding.profileFields)
        assertTrue(draft.text!!.contains("Typical Sunday"))
        assertFalse("the placeholder names the question, not the answer", draft.text!!.contains("farmers market"))

        assertTrue(api.deleteConversationProfileLink(conv) is ApiResult.Success)
        assertEquals(ErrorCode.NOT_FOUND, api.deleteConversationProfileLink(conv).errorOrNull()!!.code)
        val plain = api.createDraft(conv, DraftCreateRequest(), IdempotencyKey.random()).getOrNull()!!
        assertNull(plain.grounding.profileId)
        assertTrue(plain.grounding.profileFields.isEmpty())

        // Deleting the profile removes its link too.
        api.putConversationProfileLink(conv, ConversationProfileLinkRequest(profile.id))
        assertTrue(api.deleteProfile(profile.id) is ApiResult.Success)
        assertEquals(ErrorCode.NOT_FOUND, api.deleteProfile(profile.id).errorOrNull()!!.code)
        assertEquals(ErrorCode.NOT_FOUND, api.deleteConversationProfileLink(conv).errorOrNull()!!.code)
        val types = api.listAgentActions().getOrNull()!!.items.map { it.type }
        assertTrue(types.containsAll(listOf(AgentActionType.PROFILE_LINKED, AgentActionType.PROFILE_UNLINKED, AgentActionType.PROFILE_DELETED)))
    }

    /** A Hinge notification connection paired against the mock; `profile_read` comes from the hook. */
    private class HingeWorld {
        val api = MockWingmanApi()
        val store = InMemoryNotificationStateStore()
        val clock = NFakeClock()
        val core = NotificationListenerCore(NotificationPluginRegistry(listOf(HingeNotificationPlugin())), store, LiveReplyActions(), clock)
        val host = NotificationSyncHost(core, NotificationReplyAdapter(core, store, clock), store, api, clock)

        init {
            core.onAccessGranted()
        }
    }

    @Test fun profileReadIsUnsupportedUnlessAnotherReaderReportsIt() {
        val w = HingeWorld()
        assertEquals(CapabilityStatus.UNSUPPORTED, w.core.capabilities("hinge").profileRead.status)
        var on = true
        ProfileReadCapability.source = ProfileReadCapabilitySource { platform ->
            if (on && platform == "hinge") CapabilityState(CapabilityStatus.VERIFIED, "synthetic reader") else null
        }
        // Only that platform, and never VERIFIED on the wire (D-016).
        assertEquals(CapabilityStatus.VERIFIED, w.core.capabilities("hinge").profileRead.status)
        val claim = w.host.pairingClaim("hinge")
        assertEquals("UNVERIFIED", claim["capabilities"]!!.jsonObject["profile_read"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        on = false
        assertEquals(CapabilityStatus.UNSUPPORTED, w.core.capabilities("hinge").profileRead.status)
    }

    @Test fun deviceImportNeedsAReportedProfileReadOnThePairedConnection() = runTest {
        val w = HingeWorld()
        assertTrue(w.host.setReadingEnabled("hinge", true, "Hinge notifications") is NotificationToggleResult.Done)
        val connection = w.host.pairingState("hinge")!!
        fun device() = ProfileImportRequest(
            provider = ProfileProvider.HINGE, externalProfileId = "screen:synthetic-2", source = ProfileSource.DEVICE,
            connectionId = connection.id, displayName = "Synthetic Riley", sourceObservedAt = Instant.parse("2026-11-01T10:00:00Z"),
        )
        // Paired through notifications only: profile_read is UNSUPPORTED, so the import is refused.
        assertEquals(ErrorCode.VALIDATION_ERROR, w.api.importProfile(device(), IdempotencyKey.random()).errorOrNull()!!.code)
        assertFalse("nothing to report for an unpaired platform", w.host.reportCapabilities("tinder", "screen_reader_on"))

        ProfileReadCapability.source = ProfileReadCapabilitySource { if (it == "hinge") CapabilityState(CapabilityStatus.UNVERIFIED, "synthetic reader") else null }
        assertTrue(w.host.reportCapabilities("hinge", "screen_reader_on"))
        w.host.sync()
        assertEquals(Verification.UNVERIFIED, w.api.getConnection(connection.id).getOrNull()!!.capabilities.capabilities.profileRead!!.status)
        assertTrue(w.api.importProfile(device(), IdempotencyKey.random()) is ApiResult.Success)

        // Switched off again: the downgrade is reported and the next import is refused.
        ProfileReadCapability.source = null
        assertTrue(w.host.reportCapabilities("hinge", "screen_reader_off"))
        w.host.sync()
        assertEquals(Verification.UNSUPPORTED, w.api.getConnection(connection.id).getOrNull()!!.capabilities.capabilities.profileRead!!.status)
        assertEquals(ErrorCode.VALIDATION_ERROR, w.api.importProfile(device(), IdempotencyKey.random()).errorOrNull()!!.code)
    }
}

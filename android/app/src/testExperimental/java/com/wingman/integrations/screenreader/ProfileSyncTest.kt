package com.wingman.integrations.screenreader

import com.wingman.core.api.ApiError
import com.wingman.core.api.ApiResult
import com.wingman.core.api.ConversationFilter
import com.wingman.core.api.IdempotencyKey
import com.wingman.core.api.PageRequest
import com.wingman.core.api.WingmanApi
import com.wingman.core.api.WingmanJson
import com.wingman.core.api.mock.MockFixtures
import com.wingman.core.api.mock.MockWingmanApi
import com.wingman.core.model.ContactSummary
import com.wingman.core.model.Conversation
import com.wingman.core.model.ConversationProfileLink
import com.wingman.core.model.ConversationProfileLinkRequest
import com.wingman.core.model.Page
import com.wingman.core.model.Problem
import com.wingman.core.model.Profile
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfileLinkOrigin
import com.wingman.core.model.ProfileProvenance
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ProfileSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** Upload through `importProfile` (source device, provider hinge), linking and deletion. */
class ProfileSyncTest {
    private val connection = "0192f3a0-0000-7000-8000-0000000000a7"

    /** Records every call; conversations and failures are scripted. */
    class Backend(private val mock: MockWingmanApi = MockWingmanApi()) : WingmanApi by mock {
        val calls = ArrayList<String>()
        val imports = ArrayList<ProfileImportRequest>()
        val links = ArrayList<Pair<String, ConversationProfileLinkRequest>>()
        val deleted = ArrayList<String>()
        var conversations: List<Conversation> = emptyList()
        var failImport: ApiResult.Failure? = null
        var failDelete: ApiResult.Failure? = null
        var failLink: ApiResult.Failure? = null
        private var n = 0

        override suspend fun importProfile(body: ProfileImportRequest, key: IdempotencyKey): ApiResult<Profile> {
            calls += "importProfile"
            failImport?.let { return it }
            imports += body
            val now = Instant.parse("2026-11-11T09:00:00Z")
            return ApiResult.Success(
                Profile(
                    id = "server-profile-${++n}", provider = body.provider, externalProfileId = body.externalProfileId, displayName = body.displayName,
                    bio = body.bio, prompts = body.prompts, attributes = body.attributes, sourceObservedAt = body.sourceObservedAt,
                    provenance = ProfileProvenance(body.source, body.connectionId, now), stale = false, latestEvaluation = null, latestAction = null,
                    createdAt = now, updatedAt = now,
                ),
            )
        }

        override suspend fun listConversations(page: PageRequest, filter: ConversationFilter): ApiResult<Page<Conversation>> {
            calls += "listConversations"
            return ApiResult.Success(Page(conversations.filter { filter.platform == null || it.platform == filter.platform }, null))
        }

        override suspend fun putConversationProfileLink(conversationId: String, body: ConversationProfileLinkRequest): ApiResult<ConversationProfileLink> {
            calls += "putConversationProfileLink"
            failLink?.let { return it }
            links += conversationId to body
            val now = Instant.parse("2026-11-11T09:00:00Z")
            return ApiResult.Success(ConversationProfileLink("link-1", conversationId, body.profileId, body.origin, now, now))
        }

        override suspend fun deleteConversationProfileLink(conversationId: String): ApiResult<Unit> {
            calls += "deleteConversationProfileLink"
            return ApiResult.Success(Unit)
        }

        override suspend fun deleteProfile(profileId: String): ApiResult<Unit> {
            calls += "deleteProfile"
            failDelete?.let { return it }
            deleted += profileId
            return ApiResult.Success(Unit)
        }
    }

    private fun conversation(id: String, name: String, thread: String?, platform: String = "hinge") =
        MockFixtures.conversations.first().copy(id = id, platform = platform, externalThreadId = thread, contact = ContactSummary("contact-$id", name))

    private fun network() = ApiResult.Failure(ApiError.Network(IOException("offline")))
    private fun refused() = ApiResult.Failure(
        ApiError.Http(422, Problem(type = "https://wingman.invalid/problems/validation_error", title = "x", status = 422, code = "validation_error")),
    )

    private class Fixture(val w: ReaderWorld, val backend: Backend, val sync: ProfileSync)

    private fun fixture(connectionId: String? = connection, screenProfiles: (String) -> ScreenProfile? = ScreenProfile.Companion::forPackage): Fixture {
        val w = ReaderWorld(screenProfiles = screenProfiles)
        val backend = Backend()
        var k = 0
        val sync = ProfileSync(backend, w.profiles, connectionId = { if (it == "hinge") connectionId else null }, log = w.log, newKey = { IdempotencyKey("sr-upload-%04d".format(++k)) })
        return Fixture(w, backend, sync)
    }

    private fun Fixture.capture(): CapturedProfile {
        w.core.onSnapshot(chatScreen(at = 500))
        w.core.onSnapshot(profileTop(at = 1_000))
        w.core.onSnapshot(profileScrolled(at = 2_000))
        w.core.onSnapshot(chatScreen(at = 2_500))
        return w.profiles.all().single()
    }

    @Test fun `a capture is uploaded as a device import for hinge with stated text only`() = runBlocking {
        val f = fixture()
        val local = f.capture()
        f.sync.syncAll()
        val body = f.backend.imports.single()
        assertEquals(ProfileProvider.HINGE, body.provider)
        assertEquals(ProfileSource.DEVICE, body.source)
        assertEquals(connection, body.connectionId)
        assertEquals("screen:${local.localId}", body.externalProfileId)
        assertEquals(Sentinels.NAME, body.displayName)
        assertNull("no bio was shown, none is sent", body.bio)
        assertEquals(4, body.prompts.size)
        assertEquals(Sentinels.A_SHOWER, body.prompts[1].answer)
        // Only the labeled basic is an attribute; unlabeled texts and anything photo-related are not sent.
        assertEquals(setOf("height"), body.attributes.keys)
        assertEquals(Sentinels.HEIGHT, body.attributes["height"]!!.jsonPrimitive.content)
        assertEquals(Instant.ofEpochMilli(2_000), body.sourceObservedAt)
        val wire = WingmanJson.encodeToJsonElement(ProfileImportRequest.serializer(), body).jsonObject
        assertEquals(setOf("provider", "external_profile_id", "source", "connection_id", "display_name", "prompts", "attributes", "source_observed_at"), wire.keys)
        assertEquals("device", wire["source"]!!.jsonPrimitive.content)
        assertEquals("hinge", wire["provider"]!!.jsonPrimitive.content)
        for (hidden in listOf(Sentinels.JOB, Sentinels.TOWN, Sentinels.PHOTO_TEXT, Sentinels.THEIRS_1, Sentinels.MINE_1, Sentinels.TYPING)) {
            assertFalse(wire.toString().contains(hidden))
        }
        val saved = f.w.profiles.all().single()
        assertEquals(UploadState.UPLOADED, saved.uploadState)
        assertEquals("server-profile-1", saved.serverProfileId)
        assertFalse(saved.needsUpload)
        // A second sync has nothing to do.
        f.sync.syncAll()
        assertEquals(1, f.backend.imports.size)
    }

    @Test fun `a name-keyed profile is never linked automatically - the user picks`() = runBlocking {
        val f = fixture()
        f.backend.conversations = listOf(
            conversation("conv-other", Sentinels.OTHER_NAME, "t-2"),
            conversation("conv-riley", Sentinels.NAME, "t-1"), // exactly one conversation has this name
            conversation("conv-sms", Sentinels.NAME, "t-9", platform = "sms"),
        )
        val local = f.capture()
        assertEquals(MatchKey(MatchKeyKind.NAME_AND_CHAT, null, Sentinels.NAME, ambiguous = false), local.matchKey)
        f.sync.syncAll()
        assertTrue("no link without a stable ID", f.backend.links.isEmpty())
        assertNull(f.w.profiles.all().single().linkedConversationId)

        val decision = f.sync.linkDecision(local.localId) as LinkDecision.Ask
        assertEquals(listOf("conv-riley", "conv-other"), decision.candidates.map { it.conversation.conversationId })
        assertEquals(listOf(true, false), decision.candidates.map { it.sameName })

        assertTrue(f.sync.linkByUser(local.localId, decision.candidates.first().conversation))
        val (conversationId, body) = f.backend.links.single()
        assertEquals("conv-riley", conversationId)
        assertEquals(ConversationProfileLinkRequest("server-profile-1", ProfileLinkOrigin.USER), body)
        val linked = f.w.profiles.all().single()
        assertEquals("conv-riley", linked.linkedConversationId)
        assertEquals("you", linked.linkedBy)

        assertTrue(f.sync.unlink(local.localId))
        assertNull(f.w.profiles.all().single().linkedConversationId)
        assertEquals("deleteConversationProfileLink", f.backend.calls.last())
    }

    @Test fun `a stable id that equals one conversation's thread id links as device_match`() = runBlocking {
        val withStable = ScreenProfile.HINGE.copy(stableIdIds = setOf("subject_id"))
        val f = fixture(screenProfiles = { withStable })
        f.backend.conversations = listOf(conversation("conv-a", "Somebody Else", "match-77"), conversation("conv-b", Sentinels.NAME, "match-78"))
        val top = profileTop()
        f.w.core.onSnapshot(top.copy(root = top.root.copy(children = top.root.children + label("match-77", 2300, id = "co.hinge.app:id/subject_id"))))
        f.w.core.flush()
        f.sync.syncAll()
        assertEquals("match-77", f.backend.imports.single().externalProfileId)
        // Linked to the conversation with the same ID, not to the one with the same name.
        assertEquals(listOf("conv-a" to ConversationProfileLinkRequest("server-profile-1", ProfileLinkOrigin.DEVICE_MATCH)), f.backend.links)
        assertEquals("matching ID", f.w.profiles.all().single().linkedBy)
    }

    @Test fun `without a paired connection the profile stays on the phone and says why`() = runBlocking {
        val f = fixture(connectionId = null)
        f.capture()
        f.sync.syncAll()
        assertTrue(f.backend.calls.isEmpty())
        val p = f.w.profiles.all().single()
        assertEquals(UploadState.WAITING_FOR_CONNECTION, p.uploadState)
        assertEquals(ProfileSync.NOTE_NO_CONNECTION, p.uploadNote)
        assertTrue(p.needsUpload)
    }

    @Test fun `a failed upload is kept and retried`() = runBlocking {
        val f = fixture()
        f.capture()
        f.backend.failImport = refused()
        f.sync.syncAll()
        assertEquals(UploadState.FAILED to ProfileSync.NOTE_REFUSED, f.w.profiles.all().single().let { it.uploadState to it.uploadNote })
        f.backend.failImport = network()
        f.sync.syncAll()
        assertEquals(ProfileSync.NOTE_RETRY, f.w.profiles.all().single().uploadNote)
        f.backend.failImport = null
        f.sync.syncAll()
        assertEquals(UploadState.UPLOADED, f.w.profiles.all().single().uploadState)
        assertEquals(1, f.backend.imports.size)
    }

    @Test fun `deleting removes it from the phone and from the account`() = runBlocking {
        val f = fixture()
        val local = f.capture()
        f.sync.syncAll()
        assertEquals(DeleteOutcome.DELETED, f.sync.delete(local.localId))
        assertEquals(listOf("server-profile-1"), f.backend.deleted)
        assertTrue(f.w.profiles.all().isEmpty())
        assertTrue(f.w.profiles.pendingDeletes().isEmpty())
        for (s in Sentinels.ALL) assertFalse(f.w.storedText().contains(s))
        assertEquals(DeleteOutcome.NOT_FOUND, f.sync.delete(local.localId))
        // Never uploaded: nothing to delete on the account.
        val g = fixture(connectionId = null)
        val only = g.capture()
        assertEquals(DeleteOutcome.DELETED, g.sync.delete(only.localId))
        assertTrue(g.backend.calls.isEmpty())
    }

    @Test fun `a delete while offline wipes the phone at once and finishes on the account later`() = runBlocking {
        val f = fixture()
        val local = f.capture()
        f.sync.syncAll()
        f.backend.failDelete = network()
        assertEquals(DeleteOutcome.DELETED_LOCALLY_SERVER_PENDING, f.sync.delete(local.localId))
        assertTrue(f.w.profiles.all().isEmpty())
        for (s in Sentinels.ALL) assertFalse(f.w.storedText().contains(s))
        assertEquals(listOf("server-profile-1"), f.w.profiles.pendingDeletes())
        f.backend.failDelete = null
        f.sync.syncAll()
        assertEquals(listOf("server-profile-1"), f.backend.deleted)
        assertTrue(f.w.profiles.pendingDeletes().isEmpty())
    }

    @Test fun `the sync log carries states and counts - never profile text`() = runBlocking {
        val f = fixture()
        f.backend.conversations = listOf(conversation("conv-riley", Sentinels.NAME, "t-1"))
        val local = f.capture()
        f.sync.syncAll()
        f.sync.linkByUser(local.localId, ConversationIdentity("conv-riley", "hinge", "t-1", Sentinels.NAME))
        f.sync.delete(local.localId)
        assertTrue(f.w.log.lines.any { it.startsWith("sr.upload") })
        assertTrue(f.w.log.lines.any { it.startsWith("sr.link") })
        assertTrue(f.w.log.lines.any { it.startsWith("sr.delete") })
        for (line in f.w.log.lines) for (s in Sentinels.ALL + listOf(Sentinels.Q_SUNDAY, Sentinels.Q_SIMPLE)) assertFalse("$line contains $s", line.contains(s))
    }
}

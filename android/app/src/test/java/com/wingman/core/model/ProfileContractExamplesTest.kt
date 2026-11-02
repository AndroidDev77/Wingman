package com.wingman.core.model

import com.wingman.core.api.WingmanJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** WING-031: every contract 1.3.1 profile example decodes into the Kotlin models and re-encodes losslessly. */
class ProfileContractExamplesTest {
    private fun example(name: String): String = File(ContractFiles.examplesDir(), name).readText()

    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it !is JsonNull }.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(e.map(::normalize))
        // `72` and `72.0` are the same JSON number (Kotlin encodes a Double with a fraction).
        is JsonPrimitive -> if (!e.isString) e.content.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: e else e
        else -> e
    }

    private fun <T> roundTrip(name: String, serializer: KSerializer<T>): T {
        val raw = example(name)
        val decoded = WingmanJson.decodeFromString(serializer, raw)
        assertEquals("round trip of $name", normalize(Json.parseToJsonElement(raw)), normalize(WingmanJson.encodeToJsonElement(serializer, decoded)))
        return decoded
    }

    @Test fun profileAndEvaluation() {
        val p = roundTrip("profile.response.json", Profile.serializer())
        assertEquals(ProfileProvider.HINGE, p.provider)
        assertEquals(ProfileSource.MANUAL, p.provenance.source)
        assertFalse(p.stale)
        assertTrue(p.latestEvaluation!!.reasons.isNotEmpty())
        assertFalse("private fields stay out of logs", p.toString().contains("karaoke"))
    }

    @Test fun reviewQueuePages() {
        roundTrip("profiles.review_queue.page.json", Page.serializer(Profile.serializer()))
        val all = roundTrip("profiles.review_queue.include_excluded.page.json", Page.serializer(Profile.serializer()))
        assertEquals(listOf("smoking"), all.items.last().latestEvaluation!!.dealbreakersHit)
    }

    @Test fun actionsAndRequests() {
        val recorded = roundTrip("profile_action.recorded.response.json", ProfileAction.serializer())
        assertEquals(ProfileActionExecution.NOT_EXECUTABLE, recorded.execution)
        assertNull(recorded.executionRequestedAt)
        val queued = roundTrip("profile_action.queued.response.json", ProfileAction.serializer())
        assertEquals(ProfileActionExecution.QUEUED, queued.execution)
        assertNull("queued is not executed", queued.executedAt)
        roundTrip("profile.import.request.json", ProfileImportRequest.serializer())
        roundTrip("profile.decision.request.json", ProfileDecisionRequest.serializer())
        roundTrip("connection.settings.put.request.json", ConnectionSettingsUpdate.serializer())
    }

    @Test fun enumsMatchTheContract() {
        val yaml = ContractFiles.openApiText()
        fun enumOf(schema: String): List<String> =
            Regex("""\n    $schema:\n(?:.*\n)*?      enum: \[([^\]]*)\]""").find(yaml)!!.groupValues[1].split(',').map { it.trim() }
        assertEquals(enumOf("ProfileProvider"), ProfileProvider.entries.map { it.wireName() })
        assertEquals(enumOf("ProfileSource"), ProfileSource.entries.map { it.wireName() })
        assertEquals(enumOf("ProfileDecision"), ProfileDecision.entries.map { it.wireName() })
        assertEquals(enumOf("ProfileActionExecution"), ProfileActionExecution.entries.map { it.wireName() })
    }
}

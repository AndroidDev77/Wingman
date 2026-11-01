package com.wingman.core.model

import com.wingman.core.api.WingmanJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** Contract v1.1.0 device-connector examples round-trip through the Kotlin models (WING-016 follow-up). */
class DeviceConnectorContractTest {

    private fun example(name: String): String = File(ContractFiles.examplesDir(), name).readText()

    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it !is JsonNull }.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(e.map(::normalize))
        else -> e
    }

    private fun <T> roundTrip(name: String, serializer: KSerializer<T>): T {
        val raw = example(name)
        val decoded = WingmanJson.decodeFromString(serializer, raw)
        assertEquals("round trip of $name", normalize(Json.parseToJsonElement(raw)), normalize(WingmanJson.encodeToJsonElement(serializer, decoded)))
        return decoded
    }

    @Test fun ingestResults() {
        assertEquals(ConnectorEventIngestOutcome.ACCEPTED, roundTrip("ingest.accepted.response.json", ConnectorEventIngestResult.serializer()).outcome)
        assertEquals(ConnectorEventIngestOutcome.DUPLICATE_EVENT, roundTrip("ingest.duplicate_event.response.json", ConnectorEventIngestResult.serializer()).outcome)
        assertEquals(ConnectorEventIngestOutcome.DUPLICATE_MESSAGE, roundTrip("ingest.duplicate_message.response.json", ConnectorEventIngestResult.serializer()).outcome)
        val ack = roundTrip("ingest.send_acknowledged.response.json", ConnectorEventIngestResult.serializer())
        assertEquals("0192f3a0-0000-7000-8000-0000000000d6", ack.messageId)
    }

    @Test fun claimRequestAndResults() {
        val req = roundTrip("outbox.claim.request.json", OutboxClaimRequest.serializer())
        assertEquals(5, req.maxInstructions)
        val res = roundTrip("outbox.claim.response.json", OutboxClaimResult.serializer())
        val i = res.instructions.single()
        assertEquals(OutboxInstructionState.LEASED, i.state)
        assertEquals("sms:+15550101234", i.externalThreadId)
        assertEquals(1, i.attempt)
        assertEquals("0192f3a0-0000-7000-8000-0000000000e3", i.authorization.approvalId)
        assertEquals(120L, i.leaseExpiresAt.epochSecond - res.serverTime.epochSecond)
        assertEquals(0, roundTrip("outbox.claim.empty.response.json", OutboxClaimResult.serializer()).instructions.size)
    }

    @Test fun nullApprovalIdAndUnknownOutcome() {
        val auth = WingmanJson.decodeFromString(OutboxAuthorization.serializer(), """{"origin":"autopilot","approval_id":null}""")
        assertNull(auth.approvalId)
        assertThrows(SerializationException::class.java) {
            WingmanJson.decodeFromString(ConnectorEventIngestResult.serializer(), example("invalid/ingest_result.unknown_outcome.json"))
        }
        // message_id is required (nullable): a missing key is a decoding error, like the contract.
        assertThrows(SerializationException::class.java) {
            WingmanJson.decodeFromString(
                ConnectorEventIngestResult.serializer(),
                """{"event_id":"0192f3a0-0000-7000-8000-000000000304","outcome":"accepted","recorded_at":"2026-10-31T19:00:01Z"}""",
            )
        }
    }

    @Test fun enumsMatchOpenApi() {
        val spec = ContractFiles.openApiText()
        fun inlineEnum(schema: String): List<String> {
            val block = spec.substringAfter("\n    $schema:\n").lineSequence().takeWhile { it.isBlank() || it.startsWith("      ") }.joinToString("\n")
            return Regex("enum: \\[([^\\]]*)]").find(block)!!.groupValues[1].split(',').map { it.trim() }
        }
        fun <E : Enum<E>> wire(values: List<E>, s: KSerializer<E>) = values.map { s.descriptor.getElementName(it.ordinal) }
        assertEquals(inlineEnum("ConnectorEventIngestOutcome"), wire(ConnectorEventIngestOutcome.entries, ConnectorEventIngestOutcome.serializer()))
        assertEquals(inlineEnum("OutboxInstructionState"), wire(OutboxInstructionState.entries, OutboxInstructionState.serializer()))
        val origins = Regex("origin:[\\s\\S]*?enum: \\[([^\\]]*)]").find(spec.substringAfter("\n    OutboxAuthorization:\n"))!!.groupValues[1].split(',').map { it.trim() }
        assertEquals(origins, wire(OutboxAuthorizationOrigin.entries, OutboxAuthorizationOrigin.serializer()))
    }
}

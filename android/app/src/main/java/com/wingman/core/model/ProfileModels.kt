@file:UseSerializers(UtcInstantSerializer::class)

package com.wingman.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/*
 * Contract v1.3.1 (ADR 0008 §7, D-025) profile schemas used by WING-031's Discovery screen:
 * `Profile`, `Evaluation`, `ProfileAction`, the import and decision requests, and
 * `ConnectionSettingsUpdate`. Nullability follows Primitives.kt. `bio`, `prompts` and
 * `attributes` are private (never log them), so [toString] redacts them.
 *
 * A recorded decision is NOT a platform action: [ProfileAction.execution] is separate and is
 * `not_executable` unless the server's execute gate passed (`queued`, which is still not executed).
 */

/** `ProfileProvider`. MOCK only for fixtures. */
@Serializable
enum class ProfileProvider {
    @SerialName("tinder") TINDER,
    @SerialName("bumble") BUMBLE,
    @SerialName("hinge") HINGE,
    @SerialName("mock") MOCK,
}

/** `ProfileSource`: how the profile reached Wingman. */
@Serializable
enum class ProfileSource {
    @SerialName("manual") MANUAL,
    @SerialName("fixture") FIXTURE,
    @SerialName("device") DEVICE,
}

/** `EvaluationReason.effect`. */
@Serializable
enum class ReasonEffect {
    @SerialName("positive") POSITIVE,
    @SerialName("negative") NEGATIVE,
    @SerialName("neutral") NEUTRAL,
}

@Serializable
data class ProfilePrompt(
    val prompt: String,
    val answer: String,
) {
    override fun toString(): String = "ProfilePrompt(<redacted>)"
}

/** `EvaluationReason`. [evidence] cites profile fields, e.g. `attributes/smoking`, `prompts/0`. */
@Serializable
data class EvaluationReason(
    val factor: String,
    val effect: ReasonEffect,
    val detail: String,
    val evidence: List<String>? = null,
)

/** `Evaluation` (WING-019): advisory only, never authorizes a swipe. */
@Serializable
data class Evaluation(
    val id: String,
    @SerialName("profile_id") val profileId: String,
    /** 0..100, or null when there is too little information to score. */
    val score: Double?,
    val reasons: List<EvaluationReason>,
    @SerialName("known_unknowns") val knownUnknowns: List<String>,
    @SerialName("intent_mode") val intentMode: IntentMode,
    @SerialName("dealbreakers_hit") val dealbreakersHit: List<String>? = null,
    val confidence: Double,
    @SerialName("evaluator_version") val evaluatorVersion: String,
    @SerialName("created_at") val createdAt: Instant,
)

/** `ProfileAction`: the user's [decision] and, separately, its platform [execution]. */
@Serializable
data class ProfileAction(
    val id: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("connection_id") val connectionId: String?,
    val decision: ProfileDecision,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val execution: ProfileActionExecution,
    @SerialName("execution_detail") val executionDetail: String?,
    @SerialName("decided_at") val decidedAt: Instant,
    @SerialName("execution_requested_at") val executionRequestedAt: Instant?,
    @SerialName("executed_at") val executedAt: Instant?,
    @SerialName("created_at") val createdAt: Instant,
)

@Serializable
data class ProfileProvenance(
    val source: ProfileSource,
    @SerialName("connection_id") val connectionId: String?,
    @SerialName("imported_at") val importedAt: Instant,
)

/** `Profile`: only provided content, nothing inferred. */
@Serializable
data class Profile(
    val id: String,
    val provider: ProfileProvider,
    @SerialName("external_profile_id") val externalProfileId: String,
    @SerialName("display_name") val displayName: String?,
    val bio: String?,
    val prompts: List<ProfilePrompt>,
    /** Only what the source provided, under its own names; absent means unknown. */
    val attributes: Map<String, JsonElement>,
    @SerialName("source_observed_at") val sourceObservedAt: Instant,
    val provenance: ProfileProvenance,
    /** Older than the freshness window or invalidated: can be decided on, never executed. */
    val stale: Boolean,
    @SerialName("latest_evaluation") val latestEvaluation: Evaluation?,
    @SerialName("latest_action") val latestAction: ProfileAction?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    override fun toString(): String =
        "Profile(id=$id, provider=$provider, stale=$stale, bio=<redacted>, prompts=${prompts.size}, " +
            "attributes=${attributes.keys.sorted()}, evaluation=${latestEvaluation?.id}, action=${latestAction?.id})"
}

/** `ProfileImportRequest`. Manual (the user pastes what they see) or fixture (debug only). */
@Serializable
data class ProfileImportRequest(
    val provider: ProfileProvider,
    @SerialName("external_profile_id") val externalProfileId: String,
    val source: ProfileSource,
    @SerialName("connection_id") val connectionId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val bio: String? = null,
    val prompts: List<ProfilePrompt> = emptyList(),
    val attributes: Map<String, JsonElement> = emptyMap(),
    @SerialName("source_observed_at") val sourceObservedAt: Instant,
) {
    init {
        require(externalProfileId.length in 1..256) { "external_profile_id must be 1..256 chars" }
        require(source != ProfileSource.DEVICE || connectionId != null) { "device import needs connection_id" }
        require(provider != ProfileProvider.MOCK || source == ProfileSource.FIXTURE) { "mock profiles are fixtures only" }
        require((displayName?.length ?: 0) <= 120) { "display_name must be at most 120 chars" }
        require((bio?.length ?: 0) <= 5000) { "bio must be at most 5000 chars" }
        require(prompts.size <= 20) { "at most 20 prompts" }
        require(attributes.size <= 50 && attributes.keys.all { ATTRIBUTE_NAME.matches(it) }) { "attribute names must be snake_case" }
    }

    override fun toString(): String =
        "ProfileImportRequest(provider=$provider, source=$source, bio=<redacted>, prompts=${prompts.size}, attributes=${attributes.keys.sorted()})"

    companion object {
        val ATTRIBUTE_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")
    }
}

/** (1.3.2) `ConversationProfileLink.origin`: never an inference, never a name match. */
@Serializable
enum class ProfileLinkOrigin {
    /** The user picked the conversation ("Which match is this?"). */
    @SerialName("user") USER,

    /** The device read the same stable platform ID on the profile and the conversation (server-checked). */
    @SerialName("device_match") DEVICE_MATCH,
}

/** (1.3.2) `ConversationProfileLinkRequest`. No name, score or confidence field exists. */
@Serializable
data class ConversationProfileLinkRequest(
    @SerialName("profile_id") val profileId: String,
    val origin: ProfileLinkOrigin = ProfileLinkOrigin.USER,
)

/** (1.3.2) `ConversationProfileLink`: the one profile reply drafts may use for a conversation. */
@Serializable
data class ConversationProfileLink(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("profile_id") val profileId: String,
    val origin: ProfileLinkOrigin,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)

/** `ProfileDecisionRequest`. [connectionId] is the connection a later execution would use. */
@Serializable
data class ProfileDecisionRequest(
    val decision: ProfileDecision,
    @SerialName("connection_id") val connectionId: String? = null,
)

/** `ConnectionSettingsUpdate` (1.3.0). Turning auto-swipe on needs a VERIFIED swipe (server-checked). */
@Serializable
data class ConnectionSettingsUpdate(
    @SerialName("auto_swipe_enabled") val autoSwipeEnabled: Boolean,
)

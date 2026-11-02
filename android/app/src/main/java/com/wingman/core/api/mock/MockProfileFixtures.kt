package com.wingman.core.api.mock

import com.wingman.core.model.Evaluation
import com.wingman.core.model.EvaluationReason
import com.wingman.core.model.IntentMode
import com.wingman.core.model.Profile
import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfilePrompt
import com.wingman.core.model.ProfileProvenance
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ProfileSource
import com.wingman.core.model.ReasonEffect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Contract 1.3.1 offline fixtures for WING-031's Discovery screen: clearly fictional profiles (no
 * real names, no photos), each with an evaluation the way the server's WING-019 rules would explain
 * it. One is stale and one hits a dealbreaker (excluded unless "show excluded"). Kept apart from
 * [MockFixtures] so the shared fixture file is untouched.
 */
object MockProfileFixtures {
    private fun id(suffix: String) = "0192f3a0-0000-7000-8000-" + suffix.padStart(12, '0')
    private fun t(raw: String): Instant = Instant.parse(raw)

    val PROFILE_RILEY = id("c3101")
    val PROFILE_SAM = id("c3102")
    val PROFILE_JO = id("c3103")
    val PROFILE_ALEX = id("c3104")

    private fun profile(
        id: String,
        provider: ProfileProvider,
        name: String,
        bio: String?,
        prompts: List<ProfilePrompt>,
        attributes: Map<String, JsonElement>,
        observed: Instant,
        evaluation: Evaluation,
    ) = Profile(
        id = id, provider = provider, externalProfileId = "fixture-${id.takeLast(4)}", displayName = name, bio = bio,
        prompts = prompts, attributes = attributes, sourceObservedAt = observed,
        provenance = ProfileProvenance(ProfileSource.MANUAL, connectionId = null, importedAt = observed.plusSeconds(5)),
        stale = false, latestEvaluation = evaluation, latestAction = null,
        createdAt = observed.plusSeconds(5), updatedAt = observed.plusSeconds(5),
    )

    private fun evaluation(
        profileId: String,
        score: Double?,
        confidence: Double,
        reasons: List<EvaluationReason>,
        unknowns: List<String>,
        dealbreakersHit: List<String> = emptyList(),
        at: Instant,
    ) = Evaluation(
        id = "e" + profileId.drop(1), profileId = profileId, score = score, reasons = reasons, knownUnknowns = unknowns,
        intentMode = IntentMode.SERIOUS, dealbreakersHit = dealbreakersHit, confidence = confidence,
        evaluatorVersion = "wing019-1.0/rules", createdAt = at,
    )

    private val now = MockFixtures.NOW

    val profiles: List<Profile> = listOf(
        profile(
            PROFILE_RILEY, ProfileProvider.HINGE, "Riley (fictional)", "Trail runner, terrible at karaoke.",
            listOf(ProfilePrompt("A shower thought I recently had", "Do fish get thirsty?")),
            mapOf("looking_for" to JsonPrimitive("Long-term relationship"), "smoking" to JsonPrimitive("No")),
            now.minusSeconds(3_600),
            evaluation(
                PROFILE_RILEY, 80.0, 0.6,
                listOf(
                    EvaluationReason("intent", ReasonEffect.POSITIVE, "States 'Long-term relationship', which fits your intent (serious).", listOf("attributes/looking_for")),
                    EvaluationReason("dealbreaker", ReasonEffect.POSITIVE, "Profile states smoking: No, which clears your dealbreaker 'smoking'.", listOf("attributes/smoking")),
                ),
                emptyList(), at = now.minusSeconds(3_500),
            ),
        ),
        profile(
            PROFILE_JO, ProfileProvider.TINDER, "Jo (fictional)", "Board games, ramen, long walks with my dog.",
            emptyList(),
            mapOf("interests" to JsonArray(listOf(JsonPrimitive("board games"), JsonPrimitive("ramen")))),
            now.minusSeconds(7_200),
            evaluation(
                PROFILE_JO, null, 0.2,
                listOf(EvaluationReason("interests", ReasonEffect.NEUTRAL, "Lists interests but nothing about what they're looking for.", listOf("attributes/interests"))),
                listOf("What they're looking for", "Smoking"), at = now.minusSeconds(7_100),
            ),
        ),
        // Stale: last seen 20 days ago (older than the 14-day window).
        profile(
            PROFILE_ALEX, ProfileProvider.BUMBLE, "Alex (fictional)", "Coffee first. Figuring things out.",
            emptyList(),
            mapOf("looking_for" to JsonPrimitive("Figuring out my dating goals")),
            now.minusSeconds(20L * 86_400),
            evaluation(
                PROFILE_ALEX, 45.0, 0.4,
                listOf(EvaluationReason("intent", ReasonEffect.NEUTRAL, "Still figuring out their goals.", listOf("attributes/looking_for"))),
                listOf("Smoking"), at = now.minusSeconds(19L * 86_400),
            ),
        ).copy(stale = true),
        // Excluded: hits the 'smoking' dealbreaker (score 0).
        profile(
            PROFILE_SAM, ProfileProvider.HINGE, "Sam (fictional)", "Coffee, crosswords, and long bike rides.",
            emptyList(),
            mapOf("looking_for" to JsonPrimitive("Long-term relationship"), "smoking" to JsonPrimitive("Yes")),
            now.minusSeconds(1_800),
            evaluation(
                PROFILE_SAM, 0.0, 0.9,
                listOf(
                    EvaluationReason("dealbreaker", ReasonEffect.NEGATIVE, "Profile states smoking: Yes (your dealbreaker 'smoking').", listOf("attributes/smoking")),
                    EvaluationReason("intent", ReasonEffect.POSITIVE, "States 'Long-term relationship', which fits your intent (serious).", listOf("attributes/looking_for")),
                ),
                emptyList(), dealbreakersHit = listOf("smoking"), at = now.minusSeconds(1_700),
            ),
        ),
    )

    /**
     * What the mock "evaluates" for a newly imported profile: unscored, with every common attribute
     * the profile did not state listed as unknown. Never infers anything.
     */
    fun evaluate(profileId: String, body: ProfileImportRequest, at: Instant): Evaluation {
        val unknowns = listOf("looking_for" to "What they're looking for", "smoking" to "Smoking", "age" to "Age")
            .filter { (key, _) -> key !in body.attributes }.map { it.second }
        val reasons = body.attributes["looking_for"]?.let {
            listOf(EvaluationReason("intent", ReasonEffect.NEUTRAL, "States what they're looking for (offline demo: not scored).", listOf("attributes/looking_for")))
        } ?: emptyList()
        return evaluation(profileId, null, 0.1, reasons, unknowns, at = at)
    }
}

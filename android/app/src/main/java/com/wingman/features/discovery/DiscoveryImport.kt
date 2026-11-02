package com.wingman.features.discovery

import com.wingman.core.model.ProfileImportRequest
import com.wingman.core.model.ProfilePrompt
import com.wingman.core.model.ProfileProvider
import com.wingman.core.model.ProfileSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/*
 * WING-031: manual profile import (the user types or pastes what a dating app showed them) and the
 * debug-only fixture feed. Pure Kotlin, JVM-tested. Only what the user entered is sent; nothing is
 * inferred, and photos are never read.
 */

/** The manual-import form as typed. Empty fields are simply not sent. */
data class ManualImportForm(
    val provider: ProfileProvider = ProfileProvider.HINGE,
    val displayName: String = "",
    val bio: String = "",
    /** One prompt per line: `Prompt | Answer`. */
    val prompts: String = "",
    /** One attribute per line: `name: value` (e.g. `looking for: Long-term`). Commas make a list after `interests`. */
    val attributes: String = "",
    /** The platform's profile ID if the user knows it; otherwise one is made up for this import. */
    val externalProfileId: String = "",
) {
    override fun toString(): String = "ManualImportForm(provider=$provider, <redacted>)"
}

/**
 * One submit of the manual-import form: its idempotency key and the time the user saw the profile.
 * Both stay the same across retries of that submit (so a lost response replays instead of creating
 * a second profile); any edit to the form starts a new attempt.
 */
data class ImportAttempt(val key: com.wingman.core.api.IdempotencyKey, val observedAt: Instant) {
    /** Used as the external profile ID when the user did not give one: stable for this attempt. */
    val generatedProfileId: String get() = "manual-${key.value}"

    companion object {
        fun fresh(now: Instant = Instant.now()) = ImportAttempt(com.wingman.core.api.IdempotencyKey.random(), now)
    }
}

sealed interface ImportParse {
    data class Ok(val request: ProfileImportRequest) : ImportParse
    data class Invalid(val errors: List<String>) : ImportParse
}

object ManualImport {
    val PLATFORMS = listOf(ProfileProvider.HINGE, ProfileProvider.TINDER, ProfileProvider.BUMBLE)
    private val LIST_ATTRIBUTES = setOf("interests", "languages", "pets")

    /** `Looking for` -> `looking_for`; null when it can't be a contract attribute name. */
    fun attributeName(raw: String): String? {
        val name = raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return name.takeIf { ProfileImportRequest.ATTRIBUTE_NAME.matches(it) }
    }

    fun parse(attempt: ImportAttempt, form: ManualImportForm): ImportParse =
        parse(form, attempt.observedAt) { attempt.generatedProfileId }

    fun parse(form: ManualImportForm, now: Instant, newId: () -> String = { "manual-" + UUID.randomUUID() }): ImportParse {
        val errors = mutableListOf<String>()
        if (form.provider !in PLATFORMS) errors += "Choose Tinder, Bumble or Hinge."
        if (form.displayName.isBlank() && form.bio.isBlank() && form.prompts.isBlank() && form.attributes.isBlank()) {
            errors += "Add at least a name, a bio, a prompt or an attribute."
        }
        if (form.displayName.length > 120) errors += "The name is too long (120 characters at most)."
        if (form.bio.length > 5000) errors += "The bio is too long (5000 characters at most)."
        val prompts = form.prompts.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val prompt = line.substringBefore('|').trim()
            val answer = line.substringAfter('|', "").trim()
            if (prompt.isEmpty() || prompt.length > 300 || answer.length > 1000) {
                errors += "Prompt lines look like: Prompt | Answer"
                null
            } else {
                ProfilePrompt(prompt, answer)
            }
        }
        if (prompts.size > 20) errors += "At most 20 prompts."
        val attributes = LinkedHashMap<String, JsonElement>()
        form.attributes.lines().filter { it.isNotBlank() }.forEach { line ->
            val name = attributeName(line.substringBefore(':'))
            val value = line.substringAfter(':', "").trim()
            if (name == null || value.isEmpty() || value.length > 500) {
                errors += "Attribute lines look like: name: value"
            } else if (name in LIST_ATTRIBUTES) {
                attributes[name] = JsonArray(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
            } else {
                attributes[name] = value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
            }
        }
        if (attributes.size > 50) errors += "At most 50 attributes."
        val external = form.externalProfileId.trim().ifEmpty { newId() }
        if (external.length > 256) errors += "The profile ID is too long."
        if (errors.isNotEmpty()) return ImportParse.Invalid(errors.distinct())
        return ImportParse.Ok(
            ProfileImportRequest(
                provider = form.provider,
                externalProfileId = external,
                source = ProfileSource.MANUAL,
                displayName = form.displayName.trim().ifEmpty { null },
                bio = form.bio.trim().ifEmpty { null },
                prompts = prompts,
                attributes = attributes,
                // The user is looking at it now; contract timestamps carry at most microseconds.
                sourceObservedAt = now.truncatedTo(ChronoUnit.SECONDS),
            ),
        )
    }
}

/**
 * Debug-only demo feed: the same six clearly fictional profiles as the backend's
 * `synthetic_profile_feed` (`provider: mock`, `source: fixture`), imported through the normal
 * `importProfile` path. Mock profiles can never be executed anywhere.
 */
object DiscoveryFixtures {
    private data class Entry(val name: String, val bio: String?, val prompts: List<ProfilePrompt>, val attributes: Map<String, JsonElement>)

    private fun s(v: String) = JsonPrimitive(v)

    private val entries = listOf(
        Entry(
            "Fixture Person A (fictional)", "Weekend hiker and amateur baker. Looking for something real.",
            listOf(ProfilePrompt("A perfect Sunday", "Trail at dawn, pastries after")),
            mapOf("age" to JsonPrimitive(32), "looking_for" to s("Long-term relationship"), "smoking" to s("No"), "interests" to JsonArray(listOf(s("hiking"), s("baking")))),
        ),
        Entry(
            "Fixture Person B (fictional)", "Night owl, synth music, terrible puns.",
            listOf(ProfilePrompt("I'm known for", "Making playlists for every mood")),
            mapOf("looking_for" to s("Short-term, open to long"), "smoking" to s("Socially")),
        ),
        Entry("Fixture Person C (fictional)", "Here for fun, nothing serious right now.", emptyList(), mapOf("looking_for" to s("Something casual"), "drinking" to s("Sometimes"))),
        Entry(
            "Fixture Person D (fictional)", "Board games, ramen, and long walks with my dog.",
            listOf(ProfilePrompt("Green flags", "Remembering the little things")),
            mapOf("interests" to JsonArray(listOf(s("board games"), s("ramen")))),
        ),
        Entry(
            "Fixture Person E (fictional)", "Figuring things out. Coffee first.",
            listOf(ProfilePrompt("Dating me is like", "A slow-burn mystery novel")),
            mapOf("looking_for" to s("Figuring out my dating goals"), "smoking" to s("Never")),
        ),
        Entry("Fixture Person F (fictional)", null, emptyList(), emptyMap()),
    )

    fun feed(now: Instant): List<ProfileImportRequest> = entries.mapIndexed { i, e ->
        ProfileImportRequest(
            provider = ProfileProvider.MOCK,
            externalProfileId = "fixture-%03d".format(i + 1),
            source = ProfileSource.FIXTURE,
            displayName = e.name,
            bio = e.bio,
            prompts = e.prompts,
            attributes = e.attributes,
            sourceObservedAt = now.truncatedTo(ChronoUnit.SECONDS),
        )
    }
}

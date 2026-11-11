package com.wingman.integrations.screenreader

import kotlinx.serialization.Serializable

/** A prompt and its answer exactly as shown, with where it came from. Private: never log. */
@Serializable
data class CapturedPrompt(
    val question: String,
    val answer: String,
    val source: EvidenceSource,
    val viewId: String? = null,
) {
    override fun toString(): String = "CapturedPrompt(<redacted>, source=$source)"
}

/** One stated basic (job, school, hometown, height ...) exactly as shown. Private: never log. */
@Serializable
data class CapturedField(
    val value: String,
    val source: EvidenceSource,
    val viewId: String? = null,
) {
    override fun toString(): String = "CapturedField(<redacted>, source=$source)"
}

/** What ONE snapshot of a profile screen showed. A scrolling profile yields several of these. */
class ProfileFragment(
    val displayName: String?,
    val nameSource: EvidenceSource?,
    val prompts: List<CapturedPrompt>,
    /** attribute name -> value, only for basics whose meaning is known (view id or unmistakable form). */
    val basics: Map<String, CapturedField>,
    /** Short texts that look like basics but whose meaning is unknown. Kept on the device only. */
    val unlabeled: List<String>,
    val stableId: String?,
) {
    val isEmpty: Boolean get() = displayName == null && prompts.isEmpty() && basics.isEmpty() && unlabeled.isEmpty()
    val fieldCount: Int get() = prompts.size + basics.size

    override fun toString(): String = "ProfileFragment(name=${displayName != null}, prompts=${prompts.size}, basics=${basics.keys.sorted()}, unlabeled=${unlabeled.size})"
}

/**
 * Reads a profile screen's TEXT. Rules:
 *  - prompts: a question above an answer. Found by view id when the [ScreenProfile] knows them, else
 *    as the two only texts of a plain (not tappable) container, the upper one short;
 *  - display name: by view id, else the first heading, else the top-most short name-like text above
 *    the first prompt;
 *  - basics: by view id; a height is also recognized by its form. Other short loose texts are
 *    returned as `unlabeled` and are NOT treated as attributes (their meaning would be a guess);
 *  - images, image descriptions and input fields are never read; nothing is inferred.
 */
object ProfileExtractor {
    const val MAX_NAME_CHARS = 40
    const val MAX_LOOSE_CHARS = 40
    const val MAX_UNLABELED = 20

    private val NAME = Regex("^\\p{L}[\\p{L}\\p{M} .'’-]{0,39}$")
    private val HEIGHT = Regex("^(\\d'\\s?\\d{1,2}\"?|\\d\\s?ft\\s?\\d{1,2}(\\s?in)?|[12]\\d{2}\\s?cm)$", RegexOption.IGNORE_CASE)

    fun extract(snapshot: ScreenSnapshot, profile: ScreenProfile): ProfileFragment {
        val blocks = ScreenText.blocks(snapshot.root)
        val used = HashSet<Int>()
        val prompts = LinkedHashMap<String, CapturedPrompt>()

        fun add(q: TextBlock, a: TextBlock, source: EvidenceSource) {
            val key = ScreenProfile.normalize(q.text)
            val existing = prompts[key]
            if (existing == null || source.ordinal < existing.source.ordinal) {
                prompts[key] = CapturedPrompt(q.text, a.text, source, q.viewId)
            }
            used += q.order
            used += a.order
        }

        // 1. By view id: a question block followed by the next answer block.
        if (profile.promptQuestionIds.isNotEmpty()) {
            var pending: TextBlock? = null
            for (b in blocks) {
                when {
                    ScreenProfile.idMatches(b.viewId, profile.promptAnswerIds) && pending != null -> {
                        add(pending, b, EvidenceSource.ID_HINT)
                        pending = null
                    }
                    ScreenProfile.idMatches(b.viewId, profile.promptQuestionIds) &&
                        !ScreenProfile.idMatches(b.viewId, profile.promptAnswerIds) -> pending = b
                }
            }
        }
        // 2. By structure: the two only texts of a plain container.
        for (p in ScreenText.pairs(snapshot.root)) {
            if (p.upper.order in used || p.lower.order in used) continue
            if (p.containerClickable || !ScreenClassifier.isPromptQuestion(p.upper, profile) || profile.isChrome(p.lower.text)) continue
            when {
                profile.isKnownPrompt(p.upper.text) -> add(p.upper, p.lower, EvidenceSource.KNOWN_PROMPT)
                ScreenClassifier.isStructuralPrompt(p, profile) -> add(p.upper, p.lower, EvidenceSource.STRUCTURE)
            }
        }
        val firstPromptOrder = used.minOrNull() ?: Int.MAX_VALUE

        // Basics by view id.
        val basics = LinkedHashMap<String, CapturedField>()
        for (b in blocks) {
            if (b.order in used) continue
            val attribute = profile.basicsIds.entries.firstOrNull { ScreenProfile.idMatches(b.viewId, listOf(it.key)) }?.value ?: continue
            if (attribute !in basics) basics[attribute] = CapturedField(b.text, EvidenceSource.ID_HINT, b.viewId)
            used += b.order
        }

        // Stable platform ID, only when the app's definition names a node that carries one.
        val stable = blocks.firstOrNull { it.order !in used && ScreenProfile.idMatches(it.viewId, profile.stableIdIds) }
        stable?.let { used += it.order }

        // Display name.
        fun nameLike(b: TextBlock) = b.order !in used && b.text.length <= MAX_NAME_CHARS && NAME.matches(b.text) && !profile.isChrome(b.text)
        var nameSource: EvidenceSource? = null
        var name = blocks.firstOrNull { it.order !in used && ScreenProfile.idMatches(it.viewId, profile.nameIds) }
        if (name != null) {
            nameSource = EvidenceSource.ID_HINT
        } else {
            val above = blocks.filter { it.order < firstPromptOrder && nameLike(it) && !it.inClickable }
            name = above.firstOrNull { it.heading } ?: above.minWithOrNull(compareBy<TextBlock> { it.bounds.top }.thenBy { it.order })
            if (name != null) nameSource = EvidenceSource.STRUCTURE
        }
        name?.let { used += it.order }

        // Loose short texts: a height is recognized by its form; the rest stay unlabeled.
        val unlabeled = ArrayList<String>()
        for (b in blocks) {
            if (b.order in used || b.inClickable || b.text.length > MAX_LOOSE_CHARS || profile.isChrome(b.text)) continue
            if (HEIGHT.matches(b.text)) {
                if ("height" !in basics) basics["height"] = CapturedField(b.text, EvidenceSource.SHAPE, b.viewId)
            } else if (unlabeled.size < MAX_UNLABELED && b.text !in unlabeled) {
                unlabeled += b.text
            }
        }
        return ProfileFragment(name?.text, nameSource, prompts.values.toList(), basics, unlabeled, stable?.text)
    }
}

/** A profile merged from every snapshot taken while the user stayed on it. Private: never log. */
class MergedProfile(
    val displayName: String?,
    val prompts: List<CapturedPrompt>,
    val basics: Map<String, CapturedField>,
    val unlabeled: List<String>,
    val stableId: String?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val snapshots: Int,
) {
    val fieldCount: Int get() = prompts.size + basics.size

    override fun toString(): String = "MergedProfile(prompts=${prompts.size}, basics=${basics.keys.sorted()}, snapshots=$snapshots)"
}

/**
 * Merges the fragments of ONE profile viewing. A scrolling profile arrives in pieces; pieces are
 * merged and de-duplicated (a prompt is keyed by its question; of two answers where one is the
 * beginning of the other, the longer one wins). [accepts] is false when a fragment clearly belongs
 * to someone else (another name or another stable ID): the caller then finishes this session.
 */
class ProfileCaptureSession(private val startedAtMillis: Long) {
    private var name: String? = null
    private var stableId: String? = null
    private val prompts = LinkedHashMap<String, CapturedPrompt>()
    private val basics = LinkedHashMap<String, CapturedField>()
    private val unlabeled = LinkedHashSet<String>()
    private var lastAt = startedAtMillis
    private var count = 0

    val lastSeenMillis: Long get() = lastAt

    fun accepts(f: ProfileFragment): Boolean {
        val n = name
        val s = stableId
        if (s != null && f.stableId != null && s != f.stableId) return false
        return n == null || f.displayName == null || ScreenProfile.normalize(n) == ScreenProfile.normalize(f.displayName)
    }

    fun merge(f: ProfileFragment, atMillis: Long) {
        if (f.isEmpty) return
        count++
        lastAt = maxOf(lastAt, atMillis)
        if (name == null) name = f.displayName
        if (stableId == null) stableId = f.stableId
        for (p in f.prompts) {
            val key = ScreenProfile.normalize(p.question)
            val existing = prompts[key]
            prompts[key] = when {
                existing == null -> p
                // The same answer seen partly (cut off by the screen edge) and fully: keep the full one.
                p.answer.length > existing.answer.length && p.answer.startsWith(existing.answer.trimEnd('…', '.', ' ')) -> p
                p.source.ordinal < existing.source.ordinal && p.answer == existing.answer -> p
                else -> existing
            }
        }
        for ((k, v) in f.basics) {
            val existing = basics[k]
            if (existing == null || v.source.ordinal < existing.source.ordinal) basics[k] = v
        }
        f.unlabeled.forEach { if (unlabeled.size < ProfileExtractor.MAX_UNLABELED) unlabeled += it }
    }

    /** The merged profile, or null when nothing worth keeping was seen (no prompt and no basic). */
    fun result(): MergedProfile? {
        if (prompts.isEmpty() && basics.isEmpty()) return null
        return MergedProfile(name, prompts.values.toList(), basics.toMap(), unlabeled.toList(), stableId, startedAtMillis, lastAt, count)
    }
}

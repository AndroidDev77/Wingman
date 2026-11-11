package com.wingman.integrations.screenreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileExtractorTest {
    private val hinge = ScreenProfile.HINGE

    @Test fun `prompts are read as question and answer with their provenance`() {
        val f = ProfileExtractor.extract(profileScrolled(), hinge)
        assertEquals(
            listOf(Sentinels.Q_SHOWER to Sentinels.A_SHOWER, Sentinels.Q_SIMPLE to Sentinels.A_SIMPLE, Sentinels.Q_CUSTOM to Sentinels.A_CUSTOM),
            f.prompts.map { it.question to it.answer },
        )
        assertEquals(listOf(EvidenceSource.KNOWN_PROMPT, EvidenceSource.KNOWN_PROMPT, EvidenceSource.STRUCTURE), f.prompts.map { it.source })
        assertNull("the name scrolled out of view", f.displayName)
    }

    @Test fun `name and basics - only a height is labeled by its form - the rest stay unlabeled`() {
        val f = ProfileExtractor.extract(profileTop(), hinge)
        assertEquals(Sentinels.NAME, f.displayName)
        assertEquals(EvidenceSource.STRUCTURE, f.nameSource)
        assertEquals(setOf("height"), f.basics.keys)
        assertEquals(CapturedField(Sentinels.HEIGHT, EvidenceSource.SHAPE), f.basics["height"])
        // Job and hometown are shown as text, but which is which would be a guess: not attributes.
        assertEquals(listOf(Sentinels.JOB, Sentinels.TOWN), f.unlabeled)
        assertEquals(2, f.prompts.size)
    }

    @Test fun `with view ids from the app definition basics and prompts are labeled by id`() {
        val p = hinge.copy(
            nameIds = setOf("subject_name"),
            promptQuestionIds = setOf("prompt_q"),
            promptAnswerIds = setOf("prompt_a"),
            basicsIds = mapOf("vital_job" to "job_title", "vital_hometown" to "hometown", "vital_school" to "school"),
        )
        val s = screen(
            ScreenReaderApps.HINGE, 1,
            label("Not The Name", 100, heading = true),
            label(Sentinels.NAME, 180, id = "co.hinge.app:id/subject_name"),
            label(Sentinels.JOB, 300, id = "co.hinge.app:id/vital_job_text"),
            label(Sentinels.TOWN, 380, id = "co.hinge.app:id/vital_hometown_text"),
            // A flat layout: question and answer are siblings of everything else.
            label("A question the list does not know", 500, id = "co.hinge.app:id/prompt_q"),
            label("Its synthetic answer", 580, id = "co.hinge.app:id/prompt_a"),
        )
        val f = ProfileExtractor.extract(s, p)
        assertEquals(Sentinels.NAME, f.displayName)
        assertEquals(EvidenceSource.ID_HINT, f.nameSource)
        assertEquals(mapOf("job_title" to Sentinels.JOB, "hometown" to Sentinels.TOWN), f.basics.mapValues { it.value.value })
        assertTrue(f.basics.values.all { it.source == EvidenceSource.ID_HINT && it.viewId != null })
        assertEquals(listOf(CapturedPrompt("A question the list does not know", "Its synthetic answer", EvidenceSource.ID_HINT, "co.hinge.app:id/prompt_q")), f.prompts)
        assertFalse("school was not shown, so it does not exist", "school" in f.basics)
    }

    @Test fun `nothing comes from photos - neither their text nor their description`() {
        val s = profileTop()
        val f = ProfileExtractor.extract(s, hinge)
        val everything = listOfNotNull(f.displayName) + f.prompts.flatMap { listOf(it.question, it.answer) } + f.basics.values.map { it.value } + f.unlabeled
        assertFalse(everything.any { it.contains(Sentinels.PHOTO_TEXT) })
        // The model has no slot for an image description's content at all: only its length.
        val photo = s.root.children.first { it.isImage }
        assertEquals(44, photo.descriptionLength)
        assertTrue(ScreenText.blocks(s.root).none { it.text == Sentinels.PHOTO_TEXT })
        for (cls in listOf("android.widget.ImageView", "androidx.appcompat.widget.AppCompatImageButton", "co.example.PhotoView", "android.view.SurfaceView")) {
            assertTrue(cls, ScreenNode(className = cls, text = "x").isImage)
        }
        assertFalse(ScreenNode(className = "android.widget.TextView", text = "x").isImage)
    }

    @Test fun `input fields and tappable rows are not profile text`() {
        val f = ProfileExtractor.extract(chatScreen(), hinge)
        assertTrue(f.prompts.isEmpty())
        assertFalse(f.unlabeled.contains(Sentinels.TYPING))
        val list = ProfileExtractor.extract(matchList(), hinge)
        assertTrue("list rows are not prompts", list.prompts.isEmpty())
    }

    @Test fun `successive snapshots of a scrolling profile are merged and de-duplicated`() {
        val w = ReaderWorld()
        assertEquals(SnapshotOutcome.PROFILE_PART, w.core.onSnapshot(profileTop(at = 1_000)))
        assertEquals(SnapshotOutcome.PROFILE_PART, w.core.onSnapshot(profileTop(at = 1_300))) // a redraw
        assertEquals(SnapshotOutcome.PROFILE_PART, w.core.onSnapshot(profileScrolled(at = 2_000)))
        assertTrue("nothing is saved while the user is still on the profile", w.profiles.all().isEmpty())
        // The user leaves the profile: the viewing is finished and saved once.
        assertEquals(SnapshotOutcome.CHAT_CONTEXT, w.core.onSnapshot(chatScreen(at = 3_000)))
        val p = w.profiles.all().single()
        assertEquals(listOf(p), w.captured)
        assertEquals(Sentinels.NAME, p.displayName)
        assertEquals(
            listOf(Sentinels.Q_SUNDAY to Sentinels.A_SUNDAY, Sentinels.Q_SHOWER to Sentinels.A_SHOWER, Sentinels.Q_SIMPLE to Sentinels.A_SIMPLE, Sentinels.Q_CUSTOM to Sentinels.A_CUSTOM),
            p.prompts.map { it.question to it.answer },
        )
        assertEquals(setOf("height"), p.basics.keys)
        assertEquals(listOf(Sentinels.JOB, Sentinels.TOWN), p.unlabeled)
        assertEquals(6, p.fieldCount) // name + 4 prompts + height
        assertEquals("hinge", p.platform)
        assertEquals(1_000L, p.firstSeenMillis)
        assertEquals(2_000L, p.lastSeenMillis)
        assertEquals(UploadState.LOCAL_ONLY, p.uploadState)
    }

    @Test fun `the viewing also ends on another screen - on idle - and on flush`() {
        for (end in listOf<(ReaderWorld) -> Unit>(
            { it.core.onSnapshot(matchList(at = 3_000)) },
            { it.core.onSnapshot(opaqueScreen(at = 3_000)) },
            { it.core.onIdle(1_000 + ScreenReaderCore.IDLE_FINISH_MS) },
            { it.core.flush() },
        )) {
            val w = ReaderWorld()
            w.core.onSnapshot(profileTop(at = 1_000))
            w.core.onIdle(1_000 + ScreenReaderCore.IDLE_FINISH_MS - 1)
            assertTrue(w.profiles.all().isEmpty())
            end(w)
            assertEquals(1, w.profiles.all().size)
            // Ending twice saves nothing more.
            w.core.flush()
            w.core.onIdle(10_000_000)
            assertEquals(1, w.captured.size)
        }
    }

    @Test fun `a different name starts a new profile instead of merging`() {
        val w = ReaderWorld()
        w.core.onSnapshot(profileTop(at = 1_000, name = Sentinels.NAME))
        val other = screen(
            ScreenReaderApps.HINGE, 1_500,
            label(Sentinels.OTHER_NAME, 150, heading = true, height = 80),
            promptCard(Sentinels.Q_SIMPLE, Sentinels.A_SIMPLE, 400),
        )
        w.core.onSnapshot(other)
        w.core.flush()
        val saved = w.profiles.all().associateBy { it.displayName }
        assertEquals(setOf(Sentinels.NAME, Sentinels.OTHER_NAME), saved.keys)
        assertEquals(listOf(Sentinels.Q_SIMPLE), saved[Sentinels.OTHER_NAME]!!.prompts.map { it.question })
        assertFalse(saved[Sentinels.NAME]!!.prompts.any { it.question == Sentinels.Q_SIMPLE })
    }

    @Test fun `a screen with no prompt and no basic is not saved as a profile`() {
        val session = ProfileCaptureSession(1)
        session.merge(ProfileFragment(Sentinels.NAME, EvidenceSource.STRUCTURE, emptyList(), emptyMap(), listOf("loose"), null), 2)
        assertNull(session.result())
        session.merge(ProfileExtractor.extract(profileTop(), hinge), 3)
        assertNotNull(session.result())
    }

    @Test fun `a long pause between snapshots is a new viewing`() {
        val w = ReaderWorld()
        w.core.onSnapshot(profileTop(at = 1_000))
        w.core.onSnapshot(profileScrolled(at = 1_000 + ScreenReaderCore.STALE_MS + 1))
        assertEquals("the first viewing was saved when the second began", 1, w.profiles.all().size)
        w.core.flush()
        // The second viewing shares a prompt and... has no name, so it is its own entry.
        assertEquals(2, w.profiles.all().size)
    }
}

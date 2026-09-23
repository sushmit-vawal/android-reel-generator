package com.reelgenerator

import androidx.room.Room
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reelgenerator.content.*
import com.reelgenerator.analysis.*
import com.reelgenerator.data.*
import com.reelgenerator.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ContentCandidatePlannerTest {
    private val clip = SourceClip("clip", "content://clip", 40000, 1920, 1080)
    private val video = SourceVideo(clip.uri, "ocean beach.mp4", 1, 100)
    private fun item(text: List<String>, count: Int? = null) = ContentLibraryItem("item", "import", "USER_CSV", "travel", "freedom", text.joinToString(" || "), JSONArray(text).toString(), "ocean", null, count, null, "hash")
    private fun request() = PlanningRequest("test", ReelCategory.TRAVEL, HumorStyle.AUTO, 0, 12, listOf(clip))
    private fun evidence(vararg clips: SourceClip = arrayOf(clip)) = clips.associate { source -> source.uri to ClipAnalysis(source.uri, "test", "test-evidence", listOf(
        ClipTemporalSegment(0, source.durationMs, listOf(SemanticTag("ocean", .94)), VibeProfile(.15, .1, .5, .4, "calm", "ocean", "wide", .8), .9, CropAssessment(.4, .8, .5))
    ), .9) }

    @Test fun allFourStructuresPreserveExactWording() = runTest {
        for (texts in listOf(listOf("The ocean can wait."), listOf("The ocean can wait.", "We have all afternoon."))) {
            for (count in 1..2) {
                val second = clip.copy(id = "second", uri = "content://second")
                val plan = ContentCandidatePlanner().select(request().copy(sources = listOf(clip, second)), listOf(item(texts, count)), listOf(video, video.copy(uri = second.uri)), emptyList(), emptyMap(), evidence(clip, second))
                assertEquals(texts, plan.textBeats.map { it.text })
                assertEquals(count, plan.clips.size)
                assertTrue(plan.metadata.notes.contains("contentItemId=item"))
                assertTrue(plan.textBeats.all { it.endMs - it.startMs >= ReadingDuration.minimumMs(it.text) })
            }
        }
    }

    @Test fun categoriesDisabledHistoryAndUnmatchedFootageExcludeImports() = runTest {
        val original = item(listOf("The ocean can wait."))
        val planner = ContentCandidatePlanner()
        for (excluded in listOf(original.copy(category = "Motivation"), original.copy(enabled = false), original.copy(tags = "", beatsJson = "[\"Airport check in.\"]", subTheme = "flight"))) {
            val plan = planner.select(request(), listOf(excluded), listOf(video), emptyList(), emptyMap(), evidence())
            assertFalse(plan.metadata.notes.contains("contentItemId=item"))
        }
        val plan = planner.select(request(), listOf(original), listOf(video), listOf("The ocean can wait!"), emptyMap(), evidence())
        assertFalse(plan.metadata.notes.contains("contentItemId=item"))
    }

    @Test fun importSelectPublishReopenPreventsReuseAndCountsExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "content-candidate-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, ReelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val repo = ContentLibraryRepository(db.dao())
            val csv = "category,text,tags\nTravel,\"The ocean can wait. || We have all afternoon.\",beach"
            assertEquals(1, repo.importCsv("test.csv", csv).imported)
            assertEquals(1, repo.importCsv("test.csv", csv).duplicates)
            val plan = ContentCandidatePlanner().select(request(), db.dao().contentItems(), listOf(video), db.dao().completedCaptions(), emptyMap(), evidence())
            db.dao().addBatch(Batch("batch", "TRAVEL", "AUTO"))
            val reel = GeneratedReel(plan.id, "batch", 0, clip.uri, plan.textBeats.joinToString("\n") { it.text }, planJson = ReelPlanCodec.encode(plan))
            db.dao().savePlannedReel(reel, listOf(GeneratedReelSegment(plan.id, 0, clip.uri, 0, plan.durationMs, 0)))
            assertEquals(0, db.dao().contentItems().single().useCount)
            db.dao().completeReel(reel.copy(outputUri = "content://output"))
            db.dao().completeReel(reel.copy(outputUri = "content://output"))
            db.close()
            db = Room.databaseBuilder(context, ReelDatabase::class.java, name).allowMainThreadQueries().build()
            assertEquals(1, db.dao().contentItems().single().useCount)
            val next = ContentCandidatePlanner().select(request().copy(id = "next"), db.dao().contentItems(), listOf(video), db.dao().completedCaptions(), emptyMap(), evidence())
            assertFalse(next.metadata.notes.any { it.startsWith("contentItemId=") })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun modelProviderCandidatesParticipateWithoutBeingRelabeledFallback() = runTest {
        val planner = ContentCandidatePlanner(TextGenerator { _, _, _ -> listOf(TextCandidate(listOf("The ocean keeps its own schedule."), "freedom", "AI_GENERATED")) })
        val plan = planner.select(request(), emptyList(), listOf(video), (0..4).map { PhaseTwoText.caption(ReelCategory.TRAVEL, HumorStyle.AUTO, it, 12) }, emptyMap(), evidence())
        assertTrue(plan.metadata.notes.contains("textSource=AI_GENERATED"))
        assertEquals("The ocean keeps its own schedule.", plan.textBeats.single().text)
    }

    @Test fun insufficientFootageDoesNotFlattenOrTruncateScript() = runTest {
        val texts = List(6) { "The ocean gives us enough room to start again and take a slower afternoon." }
        val plan = ContentCandidatePlanner().select(request(), listOf(item(texts)), listOf(video), emptyList(), emptyMap(), evidence())
        assertFalse(plan.metadata.notes.contains("contentItemId=item"))
    }
}

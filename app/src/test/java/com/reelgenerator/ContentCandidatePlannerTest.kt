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
            assertTrue(planner.candidates(request(), listOf(excluded), listOf(video), emptyList(), emptyMap(), evidence(), emptyList()).isEmpty())
        }
        assertTrue(planner.candidates(request(), listOf(original.copy(useCount = 1)), listOf(video), listOf("The ocean can wait!"), emptyMap(), evidence(), emptyList()).isEmpty())
    }

    @Test fun importSelectPublishReopenPreventsReuseAndCountsExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "content-candidate-test.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, ReelDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val repo = ContentLibraryRepository(db.dao())
            val csv = "category,text,tags\nTravel,\"The ocean can wait. || We have all afternoon.\",beach"
            val firstImport = repo.importCsv("test.csv", csv)
            assertEquals(1, firstImport.imported)
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
            assertTrue(ContentCandidatePlanner().candidates(request().copy(id = "next"), db.dao().contentItems(), listOf(video), db.dao().completedCaptions(), emptyMap(), evidence(), db.dao().recentPairings()).isEmpty())
            db.dao().resetUsedContent()
            assertEquals(0, db.dao().contentItems().single().useCount)
            assertTrue(ContentCandidatePlanner().candidates(request().copy(id = "reset"), db.dao().contentItems(), listOf(video), db.dao().completedCaptions(), emptyMap(), evidence(), db.dao().recentPairings()).isNotEmpty())
            ContentLibraryRepository(db.dao()).deleteImport(firstImport.importId)
            val reimport = ContentLibraryRepository(db.dao()).importCsv("different.csv", "category,text,tags\nTravel,\"The ocean can wait! || We have all afternoon\",beach")
            assertEquals(0, reimport.imported)
            assertEquals(1, reimport.previouslyUsed)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun modelProviderCandidatesParticipateWithoutBeingRelabeledFallback() = runTest {
        val planner = ContentCandidatePlanner(TextGenerator { _, _, _ -> listOf(TextCandidate(listOf("The ocean keeps its own schedule."), "freedom", "AI_GENERATED")) })
        assertTrue(planner.candidates(request(), emptyList(), listOf(video), emptyList(), emptyMap(), evidence(), emptyList()).isEmpty())
    }

    @Test fun insufficientFootageDoesNotFlattenOrTruncateScript() = runTest {
        val texts = List(6) { "The ocean gives us enough room to start again and take a slower afternoon." }
        assertTrue(ContentCandidatePlanner().candidates(request(), listOf(item(texts)), listOf(video), emptyList(), emptyMap(), evidence(), emptyList()).isEmpty())
    }

    @Test fun reservationIsAtomicAndCancellationReturnsTextToAvailable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReelDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().addContent(item(listOf("The ocean can wait.")))
            assertEquals(1, db.dao().reserveContent("item"))
            assertEquals(-1, db.dao().contentItems().single().useCount)
            assertEquals(0, db.dao().reserveContent("item"))
            db.dao().releaseContent("item")
            assertEquals(0, db.dao().contentItems().single().useCount)
            assertEquals(1, db.dao().reserveContent("item"))
            db.dao().releaseStaleContentReservations()
            assertEquals(0, db.dao().contentItems().single().useCount)
        } finally { db.close() }
    }
}

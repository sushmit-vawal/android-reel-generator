package com.reelgenerator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.reelgenerator.data.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReelDatabaseTest {
    private lateinit var db: ReelDatabase
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("test.db")
        db = Room.databaseBuilder(context, ReelDatabase::class.java, "test.db").allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close(); context.deleteDatabase("test.db") }
    @Test fun foldersSettingsAndUsageSurviveReopeningDatabase() = runTest {
        db.dao().addFolder(SourceFolder("folder", "Trips"))
        db.dao().replaceScan("folder", listOf(SourceVideo("video", "Video", 1, 10)))
        db.dao().used("video", 123)
        db.dao().saveSettings(AppSettings(category = "HUMOR", humor = "DARK"))
        db.close()
        db = Room.databaseBuilder(context, ReelDatabase::class.java, "test.db").allowMainThreadQueries().build()
        assertEquals("Trips", db.dao().folders().single().name)
        assertEquals("DARK", db.dao().settings()?.humor)
        assertEquals(1, db.dao().candidates().single().useCount)
    }
    @Test fun rescansPreserveUsageAndRemoveStaleLinks() = runTest {
        val dao = db.dao()
        dao.addFolder(SourceFolder("folder", "Clips"))
        dao.replaceScan("folder", listOf(SourceVideo("old", "Old", 1, 10), SourceVideo("keep", "Keep", 1, 10)))
        dao.used("keep", 123)
        dao.replaceScan("folder", listOf(SourceVideo("keep", "Updated", 2, 20), SourceVideo("new", "New", 1, 10)))
        assertEquals(setOf("keep", "new"), dao.candidates().map { it.uri }.toSet())
        assertEquals(1, dao.candidates().first { it.uri == "keep" }.useCount)
        assertEquals("Updated", dao.candidates().first { it.uri == "keep" }.name)
    }
    @Test fun disabledRevokedAndRemovedFoldersAreExcludedAndOverlapsDeduplicate() = runTest {
        val dao = db.dao()
        dao.addFolder(SourceFolder("a", "A")); dao.addFolder(SourceFolder("b", "B"))
        dao.replaceScan("a", listOf(SourceVideo("a/video", "Clip", 1, 10, documentKey = "same")))
        dao.replaceScan("b", listOf(SourceVideo("b/video", "Clip", 1, 10, documentKey = "same")))
        assertEquals(1, dao.candidates().size)
        dao.enableFolder("a", false)
        assertEquals("b/video", dao.candidates().single().uri)
        dao.scanError("b", "revoked", 123)
        assertTrue(dao.candidates().isEmpty())
        dao.enableFolder("a", true)
        dao.removeFolder("b")
        assertEquals("a/video", dao.candidates().single().uri)
    }
    @Test fun completionCheckpointIsIdempotent() = runTest {
        val dao = db.dao()
        dao.addFolder(SourceFolder("folder", "Clips"))
        dao.replaceScan("folder", listOf(SourceVideo("source", "Clip", 1, 10)))
        dao.addBatch(Batch("batch", "TRAVEL", "AUTO"))
        val reel = GeneratedReel("reel", "batch", 0, "source", "Caption", "output")
        dao.completeReel(reel); dao.completeReel(reel)
        assertEquals(1, dao.reels("batch").size)
        assertEquals(1, dao.candidates().single().useCount)
        assertEquals("output", dao.reels("batch").single().outputUri)
    }
    @Test fun lateProgressCannotOverwriteCancellation() = runTest {
        val dao = db.dao()
        dao.addBatch(Batch("cancelled", "TRAVEL", "AUTO"))
        dao.batchStatus("cancelled", "CANCELLED", "Cancelled")
        assertEquals(0, dao.advanceBatch("cancelled", "RUNNING", "Late progress"))
        assertEquals(0, dao.advanceBatch("cancelled", "COMPLETE", "Late completion"))
        assertEquals("CANCELLED", dao.batch("cancelled")?.status)
    }
    @Test fun migrationRetainsPhaseTwoFoldersSettingsAndCompletedReels() = runTest {
        db.close()
        val schema = JSONObject(requireNotNull(javaClass.getResourceAsStream("/com.reelgenerator.data.ReelDatabase/1.json")).bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase("test.db", Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: continue
                for (i in 0 until indices.length()) old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (index in 0 until queries.length()) old.execSQL(queries.getString(index))
            old.execSQL("INSERT INTO SourceFolder VALUES ('folder','Trips',1,1,123,NULL)")
            old.execSQL("INSERT INTO SourceVideo VALUES ('video','Clip',1,10,'key',4,123)")
            old.execSQL("INSERT INTO FolderVideo VALUES ('folder','video')")
            old.execSQL("INSERT INTO Batch VALUES ('old','TRAVEL','AUTO',123,'COMPLETE','5 reels created')")
            old.execSQL("INSERT INTO GeneratedReel VALUES ('reel','old',0,'video','Original caption','content://saved')")
            old.execSQL("INSERT INTO AppSettings VALUES (1,'HUMOR','DARK')")
            old.version = 1
        }
        db = Room.databaseBuilder(context, ReelDatabase::class.java, "test.db").addMigrations(ReelDatabase.MIGRATION_1_2, ReelDatabase.MIGRATION_2_3, ReelDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        assertEquals("Trips", db.dao().folders().single().name)
        assertEquals(4, db.dao().candidates().single().useCount)
        assertEquals("DARK", db.dao().settings()?.humor)
        val reel = db.dao().reels("old").single()
        assertEquals("content://saved", reel.outputUri); assertNull(reel.planJson)
        assertTrue(db.dao().segments("reel").isEmpty())
    }
    @Test fun multiclipsIncrementEverySourceOnceAndPersistPlan() = runTest {
        val dao = db.dao()
        dao.addFolder(SourceFolder("folder", "Clips"))
        dao.replaceScan("folder", listOf(SourceVideo("a", "A", 1, 10), SourceVideo("b", "B", 1, 10)))
        dao.addBatch(Batch("batch", "TRAVEL", "AUTO"))
        val reel = GeneratedReel("multi", "batch", 0, "a", "Caption", planJson = "persisted snapshot")
        dao.savePlannedReel(reel, listOf(GeneratedReelSegment("multi", 0, "a", 1000, 4000, 0), GeneratedReelSegment("multi", 1, "b", 2000, 5000, 3000)))
        dao.completeReel(reel.copy(outputUri = "output")); dao.completeReel(reel.copy(outputUri = "output"))
        assertEquals(listOf(1, 1), dao.candidates().map { it.useCount })
        assertEquals("persisted snapshot", dao.reels("batch").single().planJson)
        assertEquals(listOf("a", "b"), dao.segments("multi").map { it.sourceUri })
    }
}

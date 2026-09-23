package com.reelgenerator

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.reelgenerator.analysis.*
import com.reelgenerator.data.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VisualIndexPersistenceTest {
    @Test fun v4UpgradePreservesImportsHistoryFoldersAndReels() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "visual-migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(requireNotNull(javaClass.getResourceAsStream("/com.reelgenerator.data.ReelDatabase/4.json")).bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO SourceFolder VALUES ('folder','Trips',1,1,123,NULL)")
            old.execSQL("INSERT INTO SourceVideo VALUES ('video','Clip',1,10,'key',4,123)")
            old.execSQL("INSERT INTO FolderVideo VALUES ('folder','video')")
            old.execSQL("INSERT INTO Batch VALUES ('batch','TRAVEL','AUTO',123,'COMPLETE','saved')")
            old.execSQL("INSERT INTO GeneratedReel VALUES ('reel','batch',0,'video','Original caption','content://saved',NULL)")
            old.execSQL("INSERT INTO ContentImport VALUES ('import','data.csv',123,100,100,0,0)")
            old.execSQL("INSERT INTO ContentLibraryItem VALUES ('item','import','USER_CSV','Travel','ocean','Keep this wording','[\"Keep this wording\"]','ocean',NULL,NULL,NULL,'hash',123,124,2,1)")
            old.execSQL("INSERT INTO GeneratedTextHistory VALUES ('history','TRAVEL','ocean','Original caption','original caption','hash','reel',123,123,1)")
            old.execSQL("INSERT INTO ReelPairingHistory VALUES ('video','original caption',0,8000,'ocean','reel',123)")
            old.execSQL("INSERT INTO ConceptHistory VALUES ('TRAVEL','ocean','calm','setup',123,1)")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, ReelDatabase::class.java, name).addMigrations(ReelDatabase.MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            val dao = db.dao()
            assertEquals(100, dao.imports().single().successfulRows)
            assertEquals("Keep this wording", dao.contentItems().single().rawText)
            assertEquals(2, dao.contentItems().single().useCount)
            assertEquals("Trips", dao.folders().single().name)
            assertEquals(4, dao.candidates().single().useCount)
            assertEquals("content://saved", dao.reels("batch").single().outputUri)
            assertTrue(dao.textUsed("original caption")); assertEquals(1, dao.recentPairings().size)
            assertTrue(dao.clipAnalyses().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun unchangedCachedFilesNeedNoDecodeAndDisabledFoldersAreExcluded() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ReelDatabase::class.java).allowMainThreadQueries().build()
        try {
            val videos = (0..2).map { SourceVideo("content://$it", "clip$it", 1, 10) }
            for (v in videos) {
                val a = ClipAnalysis(v.uri, VisualClipAnalyzer.fingerprint(v), VisualClipAnalyzer.MODEL, emptyList(), .6)
                db.dao().saveClipAnalysis(CachedClipAnalysis(v.uri, a.fingerprint, ClipAnalysisCodec.encode(a), 10000, 100, 200, System.currentTimeMillis()))
            }
            LibraryVisualIndex(context, db.dao(), listOf(videos[0], videos[1].copy(modified = 5))).use { index ->
                index.load(1)
                assertEquals(setOf(videos[0].uri), index.sources.keys)
                assertEquals(1, index.remaining)
                assertFalse(index.analyses.containsKey(videos[2].uri))
            }
        } finally { db.close() }
    }
}

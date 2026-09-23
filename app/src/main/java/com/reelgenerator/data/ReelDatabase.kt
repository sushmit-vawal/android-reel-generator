package com.reelgenerator.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import com.reelgenerator.content.TextIdentity
import com.reelgenerator.content.ContentCsvParser
import com.reelgenerator.planning.ReelPlanCodec

@Entity
data class SourceFolder(
    @PrimaryKey val uri: String,
    val name: String,
    val enabled: Boolean = true,
    val videoCount: Int = 0,
    val lastScanned: Long = 0,
    val error: String? = null
)

@Entity
data class SourceVideo(
    @PrimaryKey val uri: String,
    val name: String,
    val modified: Long,
    val size: Long,
    val documentKey: String = uri,
    val useCount: Int = 0,
    val lastUsed: Long = 0
)

@Entity(primaryKeys = ["folderUri", "videoUri"], indices = [Index("videoUri")], foreignKeys = [
    ForeignKey(entity = SourceFolder::class, parentColumns = ["uri"], childColumns = ["folderUri"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = SourceVideo::class, parentColumns = ["uri"], childColumns = ["videoUri"], onDelete = ForeignKey.CASCADE)
])
data class FolderVideo(val folderUri: String, val videoUri: String)

@Entity
data class Batch(
    @PrimaryKey val id: String,
    val category: String,
    val humor: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "RUNNING",
    val message: String = "Scanning folders…"
)

@Entity(indices = [Index("batchId")])
data class GeneratedReel(
    @PrimaryKey val id: String,
    val batchId: String,
    val slot: Int,
    val sourceUri: String,
    val caption: String,
    val outputUri: String? = null,
    val planJson: String? = null
)

@Entity(primaryKeys = ["reelId", "position"], indices = [Index("sourceUri")], foreignKeys = [
    ForeignKey(entity = GeneratedReel::class, parentColumns = ["id"], childColumns = ["reelId"], onDelete = ForeignKey.CASCADE)
])
data class GeneratedReelSegment(val reelId: String, val position: Int, val sourceUri: String, val trimStartMs: Long, val trimEndMs: Long, val outputStartMs: Long)

@Entity
data class AppSettings(@PrimaryKey val id: Int = 1, val category: String = "TRAVEL", val humor: String = "AUTO")

@Entity(indices = [Index("normalizedText", unique = true), Index("category")])
data class GeneratedTextHistory(@PrimaryKey val id: String, val category: String, val subTheme: String, val fullText: String, val normalizedText: String, val exactHash: String, val reelId: String, val createdAt: Long = System.currentTimeMillis(), val lastUsedAt: Long = createdAt, val usageCount: Int = 1)

@Entity(primaryKeys = ["category", "concept"], indices = [Index("lastUsedAt")])
data class ConceptHistory(val category: String, val concept: String, val emotionalAngle: String, val hookType: String, val lastUsedAt: Long = System.currentTimeMillis(), val useCount: Int = 1)

@Entity(primaryKeys = ["sourceUri", "normalizedText", "sourceStartMs", "sourceEndMs"])
data class ReelPairingHistory(val sourceUri: String, val normalizedText: String, val sourceStartMs: Long, val sourceEndMs: Long, val concept: String, val reelId: String, val createdAt: Long = System.currentTimeMillis())

@Entity(indices = [Index("normalizedHash", unique = true), Index("importId")])
data class ContentLibraryItem(@PrimaryKey val id: String, val importId: String?, val sourceType: String, val category: String, val subTheme: String, val rawText: String, val beatsJson: String, val tags: String, val style: String?, val preferredClipCount: Int?, val notes: String?, val normalizedHash: String, val importedAt: Long = System.currentTimeMillis(), val lastUsedAt: Long = 0, val useCount: Int = 0, val enabled: Boolean = true)

@Entity
data class ContentImport(@PrimaryKey val id: String, val filename: String, val importedAt: Long, val totalRows: Int, val successfulRows: Int, val duplicateRows: Int, val invalidRows: Int)

@Entity
data class CachedClipAnalysis(@PrimaryKey val sourceUri: String, val fingerprint: String, val analysisJson: String,
    val durationMs: Long, val width: Int, val height: Int, val analyzedAt: Long)

@Dao
abstract class ReelDao {
    @Query("SELECT * FROM CachedClipAnalysis") abstract suspend fun clipAnalyses(): List<CachedClipAnalysis>
    @Upsert abstract suspend fun saveClipAnalysis(analysis: CachedClipAnalysis)
    @Query("SELECT l.* FROM FolderVideo l JOIN SourceFolder f ON f.uri=l.folderUri WHERE f.enabled=1 AND f.error IS NULL")
    abstract suspend fun enabledVideoLinks(): List<FolderVideo>
    @Query("SELECT * FROM ReelPairingHistory ORDER BY createdAt DESC LIMIT 2000") abstract suspend fun recentPairings(): List<ReelPairingHistory>
    @Query("SELECT * FROM SourceFolder ORDER BY name") abstract fun observeFolders(): Flow<List<SourceFolder>>
    @Query("SELECT * FROM SourceFolder ORDER BY name") abstract suspend fun folders(): List<SourceFolder>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addFolder(folder: SourceFolder)
    @Query("UPDATE SourceFolder SET enabled=:enabled WHERE uri=:uri") abstract suspend fun enableFolder(uri: String, enabled: Boolean)
    @Query("DELETE FROM SourceFolder WHERE uri=:uri") abstract suspend fun removeFolder(uri: String)
    @Query("UPDATE SourceFolder SET error=:error, lastScanned=:time WHERE uri=:uri") abstract suspend fun scanError(uri: String, error: String, time: Long)
    @Query("UPDATE SourceFolder SET videoCount=:count, lastScanned=:time, error=NULL WHERE uri=:uri") abstract suspend fun scanned(uri: String, count: Int, time: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addVideos(videos: List<SourceVideo>)
    @Query("UPDATE SourceVideo SET name=:name, modified=:modified, size=:size WHERE uri=:uri") abstract suspend fun metadata(uri: String, name: String, modified: Long, size: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun linkVideos(links: List<FolderVideo>)
    @Query("DELETE FROM FolderVideo WHERE folderUri=:uri") abstract suspend fun clearLinks(uri: String)
    @Transaction open suspend fun replaceScan(folder: String, videos: List<SourceVideo>) {
        addVideos(videos)
        videos.forEach { metadata(it.uri, it.name, it.modified, it.size) }
        clearLinks(folder)
        linkVideos(videos.map { FolderVideo(folder, it.uri) })
        scanned(folder, videos.size, System.currentTimeMillis())
    }
    @Query("SELECT v.* FROM SourceVideo v JOIN FolderVideo l ON l.videoUri=v.uri JOIN SourceFolder f ON f.uri=l.folderUri WHERE f.enabled=1 AND f.error IS NULL GROUP BY v.documentKey ORDER BY v.useCount, v.lastUsed, v.uri")
    abstract suspend fun candidates(): List<SourceVideo>
    @Query("SELECT COUNT(*) > 0 FROM GeneratedTextHistory WHERE normalizedText=:normalized") abstract suspend fun textUsed(normalized: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun recordText(history: GeneratedTextHistory)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun recordConcept(history: ConceptHistory)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun recordPairing(history: ReelPairingHistory)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addContent(item: ContentLibraryItem): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun addImport(import: ContentImport)
    @Query("SELECT * FROM ContentLibraryItem WHERE enabled=1 ORDER BY lastUsedAt, importedAt") abstract suspend fun contentItems(): List<ContentLibraryItem>
    @Query("SELECT * FROM ContentImport ORDER BY importedAt DESC") abstract suspend fun imports(): List<ContentImport>
    @Query("SELECT caption FROM GeneratedReel WHERE outputUri IS NOT NULL") abstract suspend fun completedCaptions(): List<String>
    @Query("UPDATE ContentLibraryItem SET useCount=useCount+1, lastUsedAt=:time WHERE id=:id") abstract suspend fun contentUsed(id: String, time: Long)
    @Query("SELECT * FROM ContentLibraryItem ORDER BY importedAt DESC") abstract fun observeContent(): Flow<List<ContentLibraryItem>>
    @Query("SELECT * FROM ContentImport ORDER BY importedAt DESC") abstract fun observeImports(): Flow<List<ContentImport>>
    @Query("UPDATE ContentLibraryItem SET enabled=:enabled WHERE id=:id") abstract suspend fun enableContent(id: String, enabled: Boolean)
    @Query("DELETE FROM ContentLibraryItem WHERE importId=:importId") abstract suspend fun deleteContentImport(importId: String)
    @Query("DELETE FROM ContentImport WHERE id=:importId") abstract suspend fun deleteImport(importId: String)
    @Query("SELECT * FROM AppSettings WHERE id=1") abstract suspend fun settings(): AppSettings?
    @Upsert abstract suspend fun saveSettings(settings: AppSettings)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addBatch(batch: Batch)
    @Query("SELECT * FROM Batch WHERE id=:id") abstract suspend fun batch(id: String): Batch?
    @Query("SELECT * FROM Batch ORDER BY createdAt DESC LIMIT 1") abstract fun observeBatch(): Flow<Batch?>
    @Query("UPDATE Batch SET status=:status, message=:message WHERE id=:id") abstract suspend fun batchStatus(id: String, status: String, message: String)
    @Query("UPDATE Batch SET status=:status, message=:message WHERE id=:id AND status!='CANCELLED'") abstract suspend fun advanceBatch(id: String, status: String, message: String): Int
    @Query("SELECT * FROM GeneratedReel WHERE batchId=:batchId ORDER BY slot") abstract suspend fun reels(batchId: String): List<GeneratedReel>
    @Query("SELECT * FROM GeneratedReel WHERE outputUri IS NOT NULL AND batchId=(SELECT id FROM Batch ORDER BY createdAt DESC LIMIT 1) ORDER BY slot")
    abstract fun observeLatestReels(): Flow<List<GeneratedReel>>
    @Upsert abstract suspend fun saveReel(reel: GeneratedReel)
    @Query("SELECT * FROM GeneratedReelSegment WHERE reelId=:id ORDER BY position") abstract suspend fun segments(id: String): List<GeneratedReelSegment>
    @Query("DELETE FROM GeneratedReelSegment WHERE reelId=:id") abstract suspend fun clearSegments(id: String)
    @Insert abstract suspend fun insertSegments(segments: List<GeneratedReelSegment>)
    @Transaction open suspend fun savePlannedReel(reel: GeneratedReel, segments: List<GeneratedReelSegment>) {
        saveReel(reel)
        clearSegments(reel.id)
        insertSegments(segments)
    }
    @Query("UPDATE SourceVideo SET useCount=useCount+1, lastUsed=:time WHERE documentKey=(SELECT documentKey FROM SourceVideo WHERE uri=:uri)") abstract suspend fun used(uri: String, time: Long)
    @Transaction open suspend fun completeReel(reel: GeneratedReel) {
        val alreadySaved = reels(reel.batchId).any { it.id == reel.id && it.outputUri != null }
        saveReel(reel)
        if (!alreadySaved) {
            val sources = segments(reel.id).map { it.sourceUri }.ifEmpty { listOf(reel.sourceUri) }.distinct()
            sources.forEach { used(it, System.currentTimeMillis()) }
            val normalized = TextIdentity.normalize(reel.caption)
            val plan = reel.planJson?.let { runCatching { ReelPlanCodec.decode(it) }.getOrNull() }
            val category = plan?.category?.name ?: batch(reel.batchId)?.category ?: "unknown"
            val concept = plan?.concept ?: "unknown"
            if (normalized.isNotEmpty()) recordText(GeneratedTextHistory(reel.id, category, concept, reel.caption, normalized, ContentCsvParser.hash(normalized), reel.id))
            segments(reel.id).forEach { segment -> recordPairing(ReelPairingHistory(segment.sourceUri, normalized, segment.trimStartMs, segment.trimEndMs, concept, reel.id)) }
            plan?.metadata?.notes?.firstOrNull { it.startsWith("contentItemId=") }?.substringAfter('=')?.let { contentUsed(it, System.currentTimeMillis()) }
        }
    }
}

@Database(entities = [SourceFolder::class, SourceVideo::class, FolderVideo::class, Batch::class, GeneratedReel::class, GeneratedReelSegment::class, AppSettings::class, GeneratedTextHistory::class, ConceptHistory::class, ReelPairingHistory::class, ContentLibraryItem::class, ContentImport::class, CachedClipAnalysis::class], version = 5, exportSchema = true)
abstract class ReelDatabase : RoomDatabase() {
    abstract fun dao(): ReelDao
    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS CachedClipAnalysis (sourceUri TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, analysisJson TEXT NOT NULL, durationMs INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, analyzedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE GeneratedReel ADD COLUMN planJson TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS GeneratedReelSegment (reelId TEXT NOT NULL, position INTEGER NOT NULL, sourceUri TEXT NOT NULL, trimStartMs INTEGER NOT NULL, trimEndMs INTEGER NOT NULL, outputStartMs INTEGER NOT NULL, PRIMARY KEY(reelId, position), FOREIGN KEY(reelId) REFERENCES GeneratedReel(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_GeneratedReelSegment_sourceUri ON GeneratedReelSegment(sourceUri)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS GeneratedTextHistory (id TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL, subTheme TEXT NOT NULL, fullText TEXT NOT NULL, normalizedText TEXT NOT NULL, exactHash TEXT NOT NULL, reelId TEXT NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, usageCount INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_GeneratedTextHistory_normalizedText ON GeneratedTextHistory(normalizedText)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_GeneratedTextHistory_category ON GeneratedTextHistory(category)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ConceptHistory (category TEXT NOT NULL, concept TEXT NOT NULL, emotionalAngle TEXT NOT NULL, hookType TEXT NOT NULL, lastUsedAt INTEGER NOT NULL, useCount INTEGER NOT NULL, PRIMARY KEY(category, concept))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ConceptHistory_lastUsedAt ON ConceptHistory(lastUsedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ReelPairingHistory (sourceUri TEXT NOT NULL, normalizedText TEXT NOT NULL, sourceStartMs INTEGER NOT NULL, sourceEndMs INTEGER NOT NULL, concept TEXT NOT NULL, reelId TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(sourceUri, normalizedText, sourceStartMs, sourceEndMs))")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ContentImport (id TEXT NOT NULL PRIMARY KEY, filename TEXT NOT NULL, importedAt INTEGER NOT NULL, totalRows INTEGER NOT NULL, successfulRows INTEGER NOT NULL, duplicateRows INTEGER NOT NULL, invalidRows INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ContentLibraryItem (id TEXT NOT NULL PRIMARY KEY, importId TEXT, sourceType TEXT NOT NULL, category TEXT NOT NULL, subTheme TEXT NOT NULL, rawText TEXT NOT NULL, beatsJson TEXT NOT NULL, tags TEXT NOT NULL, style TEXT, preferredClipCount INTEGER, notes TEXT, normalizedHash TEXT NOT NULL, importedAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, useCount INTEGER NOT NULL, enabled INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ContentLibraryItem_normalizedHash ON ContentLibraryItem(normalizedHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ContentLibraryItem_importId ON ContentLibraryItem(importId)")
            }
        }
        @Volatile private var instance: ReelDatabase? = null
        fun get(context: Context): ReelDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReelDatabase::class.java, "reelgenerator.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}

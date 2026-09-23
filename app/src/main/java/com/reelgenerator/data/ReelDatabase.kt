package com.reelgenerator.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

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

@Dao
abstract class ReelDao {
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
        }
    }
}

@Database(entities = [SourceFolder::class, SourceVideo::class, FolderVideo::class, Batch::class, GeneratedReel::class, GeneratedReelSegment::class, AppSettings::class], version = 2, exportSchema = true)
abstract class ReelDatabase : RoomDatabase() {
    abstract fun dao(): ReelDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE GeneratedReel ADD COLUMN planJson TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS GeneratedReelSegment (reelId TEXT NOT NULL, position INTEGER NOT NULL, sourceUri TEXT NOT NULL, trimStartMs INTEGER NOT NULL, trimEndMs INTEGER NOT NULL, outputStartMs INTEGER NOT NULL, PRIMARY KEY(reelId, position), FOREIGN KEY(reelId) REFERENCES GeneratedReel(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_GeneratedReelSegment_sourceUri ON GeneratedReelSegment(sourceUri)")
            }
        }
        @Volatile private var instance: ReelDatabase? = null
        fun get(context: Context): ReelDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReelDatabase::class.java, "reelgenerator.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

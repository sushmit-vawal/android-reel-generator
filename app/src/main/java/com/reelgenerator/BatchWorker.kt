package com.reelgenerator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.*
import com.reelgenerator.data.*
import com.reelgenerator.analysis.*
import com.reelgenerator.content.ContentCandidatePlanner
import com.reelgenerator.content.NoVisualMatchException
import com.reelgenerator.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@UnstableApi
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = ReelDatabase.get(context).dao()
    private val batchId = id.toString()
    override suspend fun doWork(): Result = batchMutex.withLock { generateBatch() }

    private suspend fun generateBatch(): Result {
        val category = ReelCategory.entries.find { it.name == inputData.getString("category") } ?: return Result.failure()
        val humor = HumorStyle.entries.find { it.name == inputData.getString("humor") } ?: HumorStyle.AUTO
        dao.addBatch(Batch(batchId, category.name, humor.name))
        if (dao.batch(batchId)?.status == "CANCELLED") return Result.success()
        var visualIndex: LibraryVisualIndex? = null
        try {
            setForeground(notification("Scanning source folders…"))
            update("Scanning source folders…")
            FolderScanner(applicationContext, dao).scanAll { update(it) }
            // Recover the publish/Room checkpoint boundary before planning any replacement source.
            dao.reels(batchId).filter { it.outputUri == null }.forEach { pending ->
                ReelExporter(applicationContext).recover(category, pending.id)?.let { output ->
                    dao.completeReel(pending.copy(outputUri = output.toString()))
                }
            }
            val sourceVideos = dao.candidates()
            val index = LibraryVisualIndex(applicationContext, dao, sourceVideos).also { visualIndex = it }
            index.load(batchId.hashCode())
            // Every batch explores another fair, randomized page. All cached clips remain eligible.
            index.expand(progress = ::update)
            val sources = sourceVideos.map { it.uri }
            val saved = dao.reels(batchId).filter { it.outputUri != null }
            val previous = saved.map { BatchOutput(it.sourceUri, requireNotNull(it.outputUri)) }
            val usage = mutableMapOf<String, Int>()
            suspend fun countUsage(reel: GeneratedReel) {
                dao.segments(reel.id).map { it.sourceUri }.ifEmpty { listOf(reel.sourceUri) }.distinct().forEach {
                    usage[it] = (usage[it] ?: 0) + 1
                }
            }
            saved.forEach { countUsage(it) }
            val candidatePlanner = ContentCandidatePlanner()
            val unusable = mutableSetOf<String>()
            val outcome = runBatch(sources, previous, sourceUseCount = { usage[it] ?: 0 }) { slot, _ ->
                currentCoroutineContext().ensureActive()
                if (dao.batch(batchId)?.status == "CANCELLED") throw CancellationException()
                update("Planning reel ${slot + 1} of 5…")
                val old = dao.reels(batchId).find { it.slot == slot }
                val ordered = index.sources.values.filterNot { it.uri in unusable }
                val request = PlanningRequest("${batchId}_$slot", category, humor, slot, batchId.hashCode(), ordered,
                    captionOverride = old?.caption?.takeIf { old.planJson == null })
                val recoveredPlan = old?.planJson?.let { json -> runCatching { ReelPlanCodec.decode(json) }.getOrNull() }
                    ?.takeIf { plan -> plan.clips.all { it.source.uri in sources && it.source.uri !in unusable } }
                suspend fun choosePlan(): ReelPlan {
                    while (true) {
                        try {
                            return candidatePlanner.select(request.copy(sources = index.sources.values.filterNot { it.uri in unusable }),
                                dao.contentItems(), sourceVideos, dao.completedCaptions(), usage, index.analyses, dao.recentPairings())
                        } catch (noMatch: NoVisualMatchException) {
                            if (index.remaining == 0) throw noMatch
                            update("Looking for a better match in more folders…")
                            index.expand(progress = ::update)
                        } finally {
                            // App-private development diagnostics, never included in normal UI or shared.
                            withContext(Dispatchers.IO) { runCatching {
                                java.io.File(applicationContext.filesDir, "visual-match-debug.txt").writeText(candidatePlanner.diagnostics.joinToString("\n"))
                            } }
                        }
                    }
                }
                val plan = recoveredPlan ?: if (old != null && old.planJson == null && ordered.isNotEmpty()) {
                    LocalReelPlanner.legacy(request.id, category, ordered.first(), old.caption)
                } else choosePlan()
                suspend fun render(planToRender: ReelPlan): String {
                    val reel = GeneratedReel(planToRender.id, batchId, slot, planToRender.clips.first().source.uri,
                        planToRender.textBeats.joinToString("\n") { it.text }, planJson = ReelPlanCodec.encode(planToRender))
                    dao.savePlannedReel(reel, planToRender.clips.mapIndexed { index, segment ->
                        GeneratedReelSegment(reel.id, index, segment.source.uri, segment.trimStartMs, segment.trimEndMs, segment.outputStartMs)
                    })
                    return try {
                        withTimeout(10 * 60 * 1000L) {
                            ReelExporter(applicationContext).export(planToRender,
                                onPublished = { uri -> dao.completeReel(reel.copy(outputUri = uri.toString())) }
                            ) { detail -> update("Reel ${slot + 1} of 5 • $detail") }.toString()
                        }
                    } catch (_: TimeoutCancellationException) {
                        dao.reels(batchId).find { it.id == reel.id }?.outputUri ?: error("A reel took too long to render.")
                    }
                }
                val output = try { render(plan) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // Never replace a completed output/checkpoint with a fallback plan.
                    val checkpoint = dao.reels(batchId).find { it.id == plan.id }
                    val published = ReelExporter(applicationContext).recover(category, plan.id)
                    if (checkpoint != null && published != null) {
                        dao.completeReel(checkpoint.copy(outputUri = published.toString()))
                        published.toString()
                    } else {
                        // Do not flatten a matched narrative onto an arbitrary first clip after an export error.
                        unusable.addAll(plan.clips.map { it.source.uri })
                        throw error
                    }
                }
                dao.reels(batchId).find { it.slot == slot }?.let { countUsage(it) }
                output
            }
            val count = outcome.outputs.size
            val folderErrors = dao.folders().filter { it.enabled }.mapNotNull { it.error }
            val detail = (outcome.errors + index.errors + folderErrors).firstOrNull()
            val message = when {
                count == 5 -> "5 reels created • Saved to Movies/Upload Reels • ${index.analyses.size}/${sourceVideos.size} videos indexed; more explored next batch"
                sources.isEmpty() -> "$count of 5 reels created. No accessible videos. Add or rescan a source folder."
                else -> "$count of 5 reels created. ${detail ?: "No more usable source videos."}"
            }
            dao.advanceBatch(batchId, if (count == 5) "COMPLETE" else "PARTIAL", message)
            return Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val count = dao.reels(batchId).count { it.outputUri != null }
                val wasCancelled = dao.batch(batchId)?.status == "CANCELLED"
                dao.batchStatus(batchId, if (wasCancelled) "CANCELLED" else "INTERRUPTED",
                    "$count of 5 reels saved. ${if (wasCancelled) "Generation cancelled." else "Generation interrupted; unfinished work may resume when Android permits."}")
            }
            throw cancelled
        } catch (error: Exception) {
            val count = dao.reels(batchId).count { it.outputUri != null }
            dao.advanceBatch(batchId, "FAILED", "$count of 5 reels saved. ${error.localizedMessage ?: "Could not start generation. Please retry."}")
            return Result.failure()
        } finally { visualIndex?.close() }
    }

    private suspend fun update(message: String) {
        currentCoroutineContext().ensureActive()
        // Cancellation can race a progress callback; never undo the persisted cancellation intent.
        if (dao.advanceBatch(batchId, "RUNNING", message) == 0) throw CancellationException()
        setForeground(notification(message))
    }

    private fun notification(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reel generation", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_reel).setContentTitle("Creating your reels").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(1001, notification, type)
    }

    companion object {
        const val WORK_NAME = "generate-five-reels"
        private const val CHANNEL = "generation"
        // WorkManager cancellation changes scheduling state before GPU teardown finishes.
        // A new user-requested batch must wait for the previous worker's cleanup.
        private val batchMutex = Mutex()
    }
}

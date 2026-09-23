package com.reelgenerator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.*
import com.reelgenerator.data.*
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
            val metadataErrors = mutableListOf<String>()
            val available = mutableListOf<SourceClip>()
            val reader = SourceClipReader(applicationContext)
            // Bounded metadata inspection, no frame analysis/AI in Phase 3A.
            dao.candidates().take(40).forEach { video ->
                currentCoroutineContext().ensureActive()
                try {
                    update("Reading clip durations… ${available.size + 1}")
                    available.add(withTimeout(15_000) { reader.read(video) })
                } catch (_: TimeoutCancellationException) { metadataErrors.add("A video provider took too long to respond.") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { metadataErrors.add(error.localizedMessage ?: "Could not read video metadata.") }
            }
            val sources = available.map { it.uri }
            val saved = dao.reels(batchId).filter { it.outputUri != null }
            val previous = saved.map { BatchOutput(it.sourceUri, requireNotNull(it.outputUri)) }
            val usage = mutableMapOf<String, Int>()
            suspend fun countUsage(reel: GeneratedReel) {
                dao.segments(reel.id).map { it.sourceUri }.ifEmpty { listOf(reel.sourceUri) }.distinct().forEach {
                    usage[it] = (usage[it] ?: 0) + 1
                }
            }
            saved.forEach { countUsage(it) }
            val planner: ReelPlanner = LocalReelPlanner()
            val outcome = runBatch(sources, previous, sourceUseCount = { usage[it] ?: 0 }) { slot, source ->
                currentCoroutineContext().ensureActive()
                if (dao.batch(batchId)?.status == "CANCELLED") throw CancellationException()
                update("Planning reel ${slot + 1} of 5…")
                val old = dao.reels(batchId).find { it.slot == slot }
                val ordered = listOf(available.first { it.uri == source }) + available.filter { it.uri != source }.sortedBy { usage[it.uri] ?: 0 }
                val request = PlanningRequest("${batchId}_$slot", category, humor, slot, batchId.hashCode(), ordered,
                    captionOverride = old?.caption?.takeIf { old.planJson == null })
                val recoveredPlan = old?.planJson?.let { json -> runCatching { ReelPlanCodec.decode(json) }.getOrNull() }
                    ?.takeIf { plan -> plan.clips.all { it.source.uri in sources } }
                val plan = recoveredPlan ?: if (old != null && old.planJson == null) {
                    LocalReelPlanner.legacy(request.id, category, ordered.first(), old.caption)
                } else try { planner.plan(request) } catch (error: IllegalArgumentException) {
                    planner.plan(request.copy(forceSingle = true, fallbackReason = error.localizedMessage ?: "Planning failed."))
                }
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
                        if (plan.clips.size == 1) throw error
                        update("Reel ${slot + 1} of 5 • Retrying with one clip…")
                        render(planner.plan(request.copy(forceSingle = true, fallbackReason = error.localizedMessage ?: "Composition failed.")))
                    }
                }
                dao.reels(batchId).find { it.slot == slot }?.let { countUsage(it) }
                output
            }
            val count = outcome.outputs.size
            val folderErrors = dao.folders().filter { it.enabled }.mapNotNull { it.error }
            val detail = (outcome.errors + metadataErrors + folderErrors).firstOrNull()
            val message = when {
                count == 5 -> "5 reels created • Saved to Movies/Upload Reels"
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
        }
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

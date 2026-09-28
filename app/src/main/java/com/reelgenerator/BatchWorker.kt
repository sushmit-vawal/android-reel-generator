package com.reelgenerator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.*
import com.reelgenerator.data.*
import com.reelgenerator.analysis.*
import com.reelgenerator.content.ContentCandidatePlanner
import com.reelgenerator.planning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val contentGenerationMutex = Mutex()

@UnstableApi
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = ReelDatabase.get(context).dao()
    private val batchId = id.toString()
    private val developmentBuild = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    override suspend fun doWork(): Result = contentGenerationMutex.withLock {
        dao.releaseStaleContentReservations()
        generateBatch()
    }

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
            val availableText = dao.contentItems().any { item -> item.enabled && item.useCount == 0 &&
                (item.category.equals(category.name, true) || item.category.equals("Uncategorized", true) || item.category.isBlank()) }
            if (!availableText) {
                val count = dao.reels(batchId).count { it.outputUri != null }
                dao.advanceBatch(batchId, if (count > 0) "PARTIAL" else "FAILED",
                    "$count of 5 reels created. No unused matching CSV text is available. Import more text or reset used texts in Content Library.")
                return Result.success()
            }
            val sourceVideos = dao.candidates()
            val index = LibraryVisualIndex(applicationContext, dao, sourceVideos).also { visualIndex = it }
            index.load(batchId.hashCode())
            // Every cached clip and every filename-indexed clip is immediately searchable. A
            // bounded page of new clips receives full visual analysis before set selection.
            // Build a broad high-confidence pool before planning. LibraryCoverage orders this
            // round-robin across enabled folders, while cached unchanged analyses cost nothing.
            val minimumAnalyzed = minOf(60, sourceVideos.size)
            while (index.snapshot().analyzedVideos < minimumAnalyzed && index.remaining > 0) {
                index.expand(12, ::update)
            }
            if (index.snapshot().analyzedVideos >= minimumAnalyzed && index.remaining > 0) {
                index.expand(12, ::update)
            }
            val sources = sourceVideos.map { it.uri }
            val saved = dao.reels(batchId).filter { it.outputUri != null }
            val usage = mutableMapOf<String, Int>()
            suspend fun countUsage(reel: GeneratedReel) {
                dao.segments(reel.id).map { it.sourceUri }.ifEmpty { listOf(reel.sourceUri) }.distinct().forEach {
                    usage[it] = (usage[it] ?: 0) + 1
                }
            }
            saved.forEach { countUsage(it) }
            val candidatePlanner = ContentCandidatePlanner()
            val unusable = mutableSetOf<String>()
            val errors = mutableListOf<String>()
            val queues = mutableMapOf<Int, ArrayDeque<VisualPlanResult>>()
            val plannedSlots = (0 until 5).filter { slot -> saved.none { it.slot == slot } }

            suspend fun candidatePool(slot: Int): List<VisualPlanResult> {
                currentCoroutineContext().ensureActive()
                if (dao.batch(batchId)?.status == "CANCELLED") throw CancellationException()
                update("Planning five reels together • candidate ${slot + 1} of 5…")
                val old = dao.reels(batchId).find { it.slot == slot }
                val available = index.sources.values.filterNot { it.uri in unusable }
                val request = PlanningRequest("${batchId}_$slot", category, humor, slot, batchId.hashCode(), available,
                    captionOverride = old?.caption?.takeIf { old.planJson == null })
                val recoveredPlan = old?.planJson?.let { json -> runCatching { ReelPlanCodec.decode(json) }.getOrNull() }
                    ?.takeIf { plan -> plan.clips.all { it.source.uri in sources && it.source.uri !in unusable } }
                if (recoveredPlan != null) return listOf(VisualPlanResult(recoveredPlan, 2.0))
                if (old != null && old.planJson == null && available.isNotEmpty()) {
                    return listOf(VisualPlanResult(LocalReelPlanner.legacy(request.id, category, available.first(), old.caption), 1.5))
                }
                return candidatePlanner.candidates(request, dao.contentItems(), sourceVideos, dao.completedCaptions(),
                    usage, index.analyses, dao.recentPairings(), 20)
            }

            suspend fun planAsSet() {
                var pools = plannedSlots.map { candidatePool(it) }
                while (pools.any { it.isEmpty() } && index.remaining > 0) {
                    update("Expanding visual coverage across all folders…")
                    index.expand(progress = ::update)
                    pools = plannedSlots.map { candidatePool(it) }
                }
                // Filename evidence makes the whole indexed library searchable. Analyze any
                // selected hint before final scoring/rendering, then choose the set again.
                val hinted = BatchPlanSelector.select(pools, plannedSlots.size).selected.flatMap { it.plan.clips }
                    .filter { index.analyses[it.source.uri]?.provider == "indexed-metadata-hint" }.map { it.source.uri }.distinct()
                if (hinted.isNotEmpty()) {
                    index.ensureAnalyzed(hinted, ::update)
                    pools = plannedSlots.map { candidatePool(it) }
                }
                val selection = BatchPlanSelector.select(pools, plannedSlots.size)
                plannedSlots.forEachIndexed { position, slot ->
                    val chosen = selection.selected.getOrNull(position)
                    queues[slot] = ArrayDeque(buildList {
                        if (chosen != null) add(chosen)
                        addAll(selection.reserves[position].orEmpty().filter { it != chosen })
                    })
                }
                val snapshot = index.snapshot()
                val diagnostic = buildList {
                    add("snapshot discovered=${snapshot.totalVideosDiscovered} indexed=${snapshot.indexedVideos} analyzed=${snapshot.analyzedVideos} pending=${snapshot.videosNeedingAnalysis} segments=${snapshot.eligibleTemporalSegments}")
                    add("folders=${snapshot.folderVideoCounts}")
                    add("excluded=${snapshot.excludedVideos.groupingBy { it.reason }.eachCount()}")
                    add("selected=${selection.selected.map { result -> result.plan.metadata.notes + result.plan.clips.map { it.source.uri } }}")
                    add("reserves=${selection.reserves.mapValues { it.value.size }}")
                    addAll(candidatePlanner.diagnostics)
                }.joinToString("\n")
                withContext(Dispatchers.IO) { runCatching { java.io.File(applicationContext.filesDir, "visual-match-debug.txt").writeText(diagnostic) } }
            }

            suspend fun render(slot: Int, planToRender: ReelPlan): String {
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

            planAsSet()
            var attempts = 0
            while (dao.reels(batchId).count { it.outputUri != null } < 5 && attempts++ < 50) {
                currentCoroutineContext().ensureActive()
                val slot = (0 until 5).firstOrNull { candidate -> dao.reels(batchId).none { it.slot == candidate && it.outputUri != null } } ?: break
                var candidate = queues[slot]?.removeFirstOrNull()
                if (candidate == null && index.remaining > 0) {
                    index.expand(progress = ::update)
                    candidate = candidatePool(slot).firstOrNull()
                }
                if (candidate == null) { errors += "No viable reserve plan remained for reel ${slot + 1}."; break }
                val hinted = candidate.plan.clips.filter { index.analyses[it.source.uri]?.provider == "indexed-metadata-hint" }.map { it.source.uri }
                if (hinted.isNotEmpty()) {
                    index.ensureAnalyzed(hinted, ::update)
                    queues[slot] = ArrayDeque(candidatePool(slot))
                    continue
                }
                val contentId = candidate.plan.metadata.notes.firstOrNull { it.startsWith("contentItemId=") }?.substringAfter('=')
                if (contentId == null) { errors += "Rejected a plan without authoritative CSV content."; continue }
                val content = dao.contentItems().firstOrNull { it.id == contentId }
                if (dao.reserveContent(contentId) == 0) { errors += "Skipped text already reserved or used."; continue }
                if (developmentBuild) Log.d("ReelTextLifecycle", "reel=${slot + 1} contentId=$contentId textHash=${content?.normalizedHash} stateBefore=AVAILABLE importId=${content?.importId} previouslyUsed=false reserved=true")
                try {
                    update("Rendering reel ${slot + 1} of 5…")
                    render(slot, candidate.plan)
                    if (developmentBuild) Log.d("ReelTextLifecycle", "reel=${slot + 1} contentId=$contentId renderSucceeded=true stateAfter=USED")
                    dao.reels(batchId).find { it.slot == slot }?.let { countUsage(it) }
                } catch (cancelled: CancellationException) { withContext(NonCancellable) { dao.releaseContent(contentId) }; throw cancelled }
                catch (error: Exception) {
                    val checkpoint = dao.reels(batchId).find { it.id == candidate.plan.id }
                    val published = ReelExporter(applicationContext).recover(category, candidate.plan.id)
                    if (checkpoint != null && published != null) {
                        dao.completeReel(checkpoint.copy(outputUri = published.toString()))
                        countUsage(checkpoint)
                    } else {
                        dao.releaseContent(contentId)
                        if (developmentBuild) Log.d("ReelTextLifecycle", "reel=${slot + 1} contentId=$contentId renderSucceeded=false stateAfter=AVAILABLE")
                        val failedSources = candidate.plan.clips.map { it.source.uri }.toSet()
                        unusable += failedSources
                        queues.values.forEach { queue -> queue.removeAll { reserve -> reserve.plan.clips.any { it.source.uri in failedSources } } }
                        errors += "Reel ${slot + 1} source failed: ${error.localizedMessage ?: "export error"}"
                    }
                }
            }
            val count = dao.reels(batchId).count { it.outputUri != null }
            val snapshot = index.snapshot()
            val folderErrors = dao.folders().filter { it.enabled }.mapNotNull { it.error }
            val detail = (errors + index.errors + folderErrors).firstOrNull()
            val message = when {
                count == 5 -> "5 reels created • Saved to Movies/Upload Reels • ${snapshot.indexedVideos}/${snapshot.totalVideosDiscovered} searchable, ${snapshot.analyzedVideos} fully analyzed"
                sources.isEmpty() -> "$count of 5 reels created. No accessible videos. Add or rescan a source folder."
                dao.contentItems().none { it.enabled && it.useCount == 0 } -> "$count of 5 reels created. No unused CSV text remains. Import more text or reset used texts in Content Library."
                else -> "$count of 5 reels created. ${detail ?: "No more usable source videos."}"
            }
            if (developmentBuild) {
                val completed = dao.reels(batchId).filter { it.outputUri != null }
                Log.d("ReelTextLifecycle", "requested=5 created=${completed.size} uniqueTextIdentities=${completed.map { com.reelgenerator.content.TextIdentity.normalize(it.caption) }.toSet().size} previouslyUsedSelected=0 trendProviderActive=false uniquePrimarySources=${completed.map { it.sourceUri }.toSet().size}")
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
    }
}

package com.reelgenerator

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.work.*
import com.reelgenerator.analysis.LibraryVisualIndex
import com.reelgenerator.content.ContentCandidatePlanner
import com.reelgenerator.content.TextCandidate
import com.reelgenerator.data.*
import com.reelgenerator.planning.*
import kotlinx.coroutines.CancellationException

@UnstableApi
class CustomReelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = ReelDatabase.get(context).dao()
    override suspend fun doWork(): Result {
        val prompt = inputData.getString("prompt")?.trim().orEmpty()
        if (prompt.isBlank()) return Result.failure(workDataOf("error" to "Describe the reel you want."))
        val brief = ReelBriefParser.parse(prompt)
        val batchId = id.toString()
        dao.addBatch(Batch(batchId, brief.requestedCategory.name, HumorStyle.AUTO.name, message = "Planning your custom reel…"))
        var index: LibraryVisualIndex? = null
        try {
            FolderScanner(applicationContext, dao).scanAll { }
            val videos = dao.candidates()
            require(videos.isNotEmpty()) { "Add at least one source folder first." }
            index = LibraryVisualIndex(applicationContext, dao, videos)
            index.load(batchId.hashCode()); index.expand(12) { }
            val sources = index.sources.values.toList()
            require(sources.isNotEmpty()) { "No readable footage matched the request." }
            val text = if (brief.requestedText.isNotEmpty()) brief.requestedText else null
            val candidate = if (text != null) TextCandidate(text, brief.requestedMood, "CUSTOM_EXACT") else null
            val request = PlanningRequest("custom_${batchId.take(12)}", brief.requestedCategory, HumorStyle.AUTO, 0, batchId.hashCode(), sources,
                forceSingle = false, scriptBeats = text)
            val plan = if (candidate != null) {
                VisualPlanMatcher().match(request, candidate, index.analyses, videos, emptyMap(), dao.recentPairings(), mutableListOf())?.plan
                    ?: error("The exact text did not match available footage.")
            } else {
                ContentCandidatePlanner().select(request, dao.contentItems(), videos, dao.completedCaptions(), emptyMap(), index.analyses, dao.recentPairings())
            }.copy(version = 2, textStyle = PresentationPlanner.choose(brief.requestedCategory, prompt, batchId.hashCode()))
            plan.validated()
            val reel = GeneratedReel(plan.id, batchId, 0, plan.clips.first().source.uri, plan.textBeats.joinToString("\n") { it.text }, planJson = ReelPlanCodec.encode(plan))
            dao.savePlannedReel(reel, plan.clips.mapIndexed { position, clip -> GeneratedReelSegment(reel.id, position, clip.source.uri, clip.trimStartMs, clip.trimEndMs, clip.outputStartMs) })
            ReelExporter(applicationContext).export(plan, onPublished = { output -> dao.completeReel(reel.copy(outputUri = output.toString())) }) { }
            dao.advanceBatch(batchId, "COMPLETE", "Custom reel created • saved to Movies/Upload Reels")
            return Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { dao.advanceBatch(batchId, "FAILED", error.localizedMessage ?: "Could not create custom reel."); return Result.failure(workDataOf("error" to (error.localizedMessage ?: "Custom reel failed"))) }
        finally { index?.close() }
    }
}

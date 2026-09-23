package com.reelgenerator.analysis

import android.content.Context
import com.reelgenerator.data.*
import com.reelgenerator.planning.*
import kotlinx.coroutines.*
import kotlin.random.Random

/** Round-robin over shuffled folders; no first-N permanent exclusion, including overlapping trees. */
object LibraryCoverage {
    fun order(videos: List<SourceVideo>, links: List<FolderVideo>, seed: Int): List<SourceVideo> {
        val random = Random(seed)
        val membership = links.groupBy { it.videoUri }
        val groups = videos.shuffled(random).groupBy { membership[it.uri]?.random(random)?.folderUri ?: "" }
            .values.shuffled(random).map { ArrayDeque(it) }
        return buildList {
            while (groups.any { it.isNotEmpty() }) groups.forEach { if (it.isNotEmpty()) add(it.removeFirst()) }
        }
    }
}

class LibraryVisualIndex(private val context: Context, private val dao: ReelDao, val videos: List<SourceVideo>) : AutoCloseable {
    val sources = linkedMapOf<String, SourceClip>()
    val analyses = linkedMapOf<String, ClipAnalysis>()
    val errors = mutableListOf<String>()
    private val pending = ArrayDeque<SourceVideo>()
    private val analyzer = VisualClipAnalyzer(context)
    val remaining get() = pending.size
    suspend fun load(seed: Int) {
        val cache = dao.clipAnalyses().associateBy { it.sourceUri }
        val fresh = mutableListOf<SourceVideo>()
        videos.forEach { video ->
            val row = cache[video.uri]
            val parsed = row?.takeIf { it.fingerprint == VisualClipAnalyzer.fingerprint(video) }
                ?.let { runCatching { ClipAnalysisCodec.decode(it.analysisJson) }.getOrNull() }
            // Providers with no stable modified/size metadata get periodic revalidation.
            val expired = row != null && (video.modified == 0L || video.size == 0L || parsed?.provider != VisualClipAnalyzer.MODEL) && System.currentTimeMillis() - row.analyzedAt > 24 * 60 * 60 * 1000
            if (row != null && parsed != null && !expired) {
                sources[video.uri] = SourceClip(video.documentKey, video.uri, row.durationMs, row.width, row.height)
                analyses[video.uri] = parsed
            } else fresh.add(video)
        }
        pending.addAll(LibraryCoverage.order(fresh, dao.enabledVideoLinks(), seed))
    }
    suspend fun expand(count: Int = 12, progress: suspend (String) -> Unit) {
        repeat(minOf(count, pending.size)) {
            currentCoroutineContext().ensureActive()
            val video = pending.removeFirst()
            progress("Understanding videos • ${analyses.size} of ${videos.size} indexed • ${pending.size + 1} to explore")
            try {
                val source = withTimeout(20_000) { SourceClipReader(context).read(video) }
                val analysis = withTimeout(45_000) { analyzer.analyze(source, video) }
                dao.saveClipAnalysis(CachedClipAnalysis(video.uri, analysis.fingerprint, ClipAnalysisCodec.encode(analysis), source.durationMs, source.width, source.height, System.currentTimeMillis()))
                sources[video.uri] = source
                analyses[video.uri] = analysis
            } catch (_: TimeoutCancellationException) { errors.add("Timed out reading ${video.name}") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { errors.add("Could not analyze ${video.name}") }
        }
    }
    override fun close() = analyzer.close()
}

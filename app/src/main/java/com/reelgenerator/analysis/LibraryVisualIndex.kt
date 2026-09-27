package com.reelgenerator.analysis

import android.content.Context
import com.reelgenerator.data.*
import com.reelgenerator.planning.*
import kotlinx.coroutines.*
import kotlin.random.Random

data class SourceExclusion(val sourceUri: String, val reason: String)
data class SourceLibrarySnapshot(
    val enabledFolders: List<SourceFolder>,
    val totalVideosDiscovered: Int,
    val indexedVideos: Int,
    val analyzedVideos: Int,
    val videosNeedingAnalysis: Int,
    val eligibleVideos: List<SourceClip>,
    val eligibleTemporalSegments: Int,
    val excludedVideos: List<SourceExclusion>,
    val folderVideoCounts: Map<String, Int>
)

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
    val exclusions = mutableListOf<SourceExclusion>()
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
            } else {
                // Indexed remains searchable while expensive inference catches up. This weak
                // evidence can rank a clip, but its lower confidence cannot displace a strong
                // image-labelled match.
                placeholder(video)?.let { (source, analysis) ->
                    sources[video.uri] = source
                    analyses[video.uri] = analysis
                }
                fresh.add(video)
            }
        }
        pending.addAll(LibraryCoverage.order(fresh, dao.enabledVideoLinks(), seed))
    }
    suspend fun expand(count: Int = 12, progress: suspend (String) -> Unit) {
        repeat(minOf(count, pending.size)) {
            currentCoroutineContext().ensureActive()
            val video = pending.removeFirst()
            analyze(video, progress)
        }
    }
    suspend fun ensureAnalyzed(uris: Collection<String>, progress: suspend (String) -> Unit) {
        uris.distinct().forEach { uri ->
            if (analyses[uri]?.provider == VisualClipAnalyzer.MODEL) return@forEach
            val video = videos.firstOrNull { it.uri == uri } ?: return@forEach
            pending.remove(video)
            analyze(video, progress)
        }
    }
    private suspend fun analyze(video: SourceVideo, progress: suspend (String) -> Unit) {
        progress("Understanding videos • ${analyses.count { it.value.provider == VisualClipAnalyzer.MODEL }} of ${videos.size} analyzed • ${pending.size} pending")
        try {
            val source = withTimeout(20_000) { SourceClipReader(context).read(video) }
            val analysis = withTimeout(45_000) { analyzer.analyze(source, video) }
            dao.saveClipAnalysis(CachedClipAnalysis(video.uri, analysis.fingerprint, ClipAnalysisCodec.encode(analysis), source.durationMs, source.width, source.height, System.currentTimeMillis()))
            sources[video.uri] = source
            analyses[video.uri] = analysis
        } catch (_: TimeoutCancellationException) { errors.add("Timed out reading ${video.name}"); exclusions.add(SourceExclusion(video.uri, "analysis-timeout")); sources.remove(video.uri); analyses.remove(video.uri) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { errors.add("Could not analyze ${video.name}"); exclusions.add(SourceExclusion(video.uri, "unreadable-or-analysis-failed")); sources.remove(video.uri); analyses.remove(video.uri) }
    }
    suspend fun snapshot(): SourceLibrarySnapshot {
        val folders = dao.folders().filter { it.enabled }
        val links = dao.enabledVideoLinks()
        val real = analyses.values.count { it.provider != "indexed-metadata-hint" }
        return SourceLibrarySnapshot(folders, videos.size, sources.size, real, pending.size,
            sources.values.toList(), analyses.values.sumOf { it.temporalSegments.size }, exclusions.toList(),
            links.groupingBy { it.folderUri }.eachCount())
    }
    private fun placeholder(video: SourceVideo): Pair<SourceClip, ClipAnalysis>? {
        val tags = VisualVocabulary.subjects(video.name).map { SemanticTag(it, .52, "indexed-filename-hint") }
        if (tags.isEmpty()) return null
        // Duration/dimensions are unknown until metadata reading, so placeholders use a safe
        // searchable envelope and are replaced before render by expand/on-demand analysis.
        val source = SourceClip(video.documentKey, video.uri, 20_000, 1080, 1920)
        val section = ClipTemporalSegment(0, 20_000, tags,
            VibeProfile(.35, .35, .5, .5, "unknown", tags.first().name, "unknown", .1), .5,
            CenterInterestCropPolicy.assess(1080, 1920, .5))
        return source to ClipAnalysis(video.uri, VisualClipAnalyzer.fingerprint(video), "indexed-metadata-hint", listOf(section), .2)
    }
    override fun close() = analyzer.close()
}

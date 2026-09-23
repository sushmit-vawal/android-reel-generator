package com.reelgenerator.planning

import com.reelgenerator.analysis.*
import com.reelgenerator.content.TextCandidate
import com.reelgenerator.data.ReelPairingHistory
import com.reelgenerator.data.SourceVideo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

data class VisualPlanResult(val plan: ReelPlan, val score: Double)

/** Beam search keeps coherent alternatives instead of committing independently to each beat. */
class VisualPlanMatcher(private val policy: MatchPolicy = MatchPolicy()) {
    private data class Option(val source: SourceClip, val section: ClipTemporalSegment, val score: VisualMatchScore)
    private data class Pick(val option: Option, val start: Long, val end: Long, val intent: BeatVisualIntent)
    private data class Path(val picks: List<Pick>, val score: Double)

    fun match(request: PlanningRequest, candidate: TextCandidate, analyses: Map<String, ClipAnalysis>, videos: List<SourceVideo>,
              usage: Map<String, Int>, pairings: List<ReelPairingHistory>, diagnostics: MutableList<String>): VisualPlanResult? {
        if (candidate.beats.isEmpty() || candidate.beats.size > 6 || candidate.beats.any { it.isBlank() || it.length > 180 }) return null
        val intents = VisualVocabulary.intents(candidate.beats, candidate.concept, candidate.tags, request.category)
        val minimums = candidate.beats.map { ReadingDuration.minimumMs(it) }
        val total = maxOf(8000L, minimums.sum() + 700)
        if (total > 20000) { if (diagnostics.size < 40) diagnostics.add("Script exceeds 20-second reading budget"); return null }
        val durations = minimums.mapIndexed { i, value -> value + (total - minimums.sum()) / minimums.size + if (i == minimums.lastIndex) (total - minimums.sum()) % minimums.size else 0 }
        val counts = videos.associate { it.uri to it.useCount }
        val relevantPairings = pairings.filter { it.concept == candidate.concept }.groupBy { it.sourceUri }
        val allOptions = intents.mapIndexed { index, intent ->
            val options = request.sources.flatMap { source -> analyses[source.uri]?.temporalSegments.orEmpty().mapNotNull { section ->
                if (section.endMs - section.startMs < minOf(1800L, durations[index])) return@mapNotNull null
                val paired = relevantPairings[source.uri].orEmpty().any { it.sourceStartMs < section.endMs && it.sourceEndMs > section.startMs }
                Option(source, section, VisualScorer.score(intent, section, (counts[source.uri] ?: 0) + (usage[source.uri] ?: 0) * 2, paired, policy))
            } }
            options.filterNot { it.score.eligible }.sortedByDescending { it.score.overall }.take(2).forEach {
                if (diagnostics.size < 40) diagnostics.add("Rejected beat=$index source=${it.source.id} range=${it.section.startMs}-${it.section.endMs} score=${it.score}")
            }
            options.filter { it.score.eligible }.shuffled(Random(request.seed + request.slot + index)).sortedByDescending { it.score.overall }.take(32)
        }
        if (allOptions.any { it.isEmpty() }) return null
        val desiredClips = candidate.preferredClips?.coerceIn(1, 5)
        val shared = allOptions.first().mapNotNull { first ->
            val matches = allOptions.map { list -> list.find { it.source.uri == first.source.uri && it.section == first.section } ?: return@mapNotNull null }
            if (first.section.endMs - first.section.startMs < total) return@mapNotNull null
            val start = first.section.startMs + (first.section.endMs - first.section.startMs - total) / 2
            var cursor = start
            Path(matches.mapIndexed { i, option -> Pick(option, cursor, cursor + durations[i], intents[i]).also { cursor += durations[i] } }, matches.sumOf { it.score.overall })
        }.maxByOrNull { it.score }
        var paths = listOf(Path(emptyList(), 0.0))
        val automaticCount = if (candidate.beats.size == 1 && allOptions.first().none { it.section.endMs - it.section.startMs >= total }) {
            val longest = allOptions.first().maxOf { it.section.endMs - it.section.startMs }.coerceAtLeast(1)
            ((total + longest - 1) / longest).toInt().coerceIn(2, 5)
        } else 1
        val units = if (candidate.beats.size == 1 && maxOf(desiredClips ?: 1, automaticCount) > 1) {
            val count = minOf(maxOf(desiredClips ?: 1, automaticCount), (total / 1800).toInt())
            (0 until count).map { 0 to (total / count + if (it == count - 1) total % count else 0) }
        } else durations.mapIndexed { i, duration -> i to duration }
        units.forEach { (beatIndex, duration) ->
            paths = paths.flatMap { path ->
                allOptions[beatIndex].mapNotNull { option ->
                    val previous = path.picks.lastOrNull()
                    val same = previous?.option?.source?.uri == option.source.uri && previous.option.section == option.section
                    val start = if (same) previous!!.end else option.section.startMs
                    if (start + duration > option.section.endMs) return@mapNotNull null
                    if (path.picks.any { it.option.source.uri == option.source.uri && start < it.end && start + duration > it.start }) return@mapNotNull null
                    val intent = intents[beatIndex]
                    val continuity = previous?.let { VisualScorer.continuity(it.option.section, option.section, abs(it.intent.desiredEnergy - intent.desiredEnergy)) } ?: 0.0
                    val repeatedShot = if (same && desiredClips != 1) .015 else 0.0
                    Path(path.picks + Pick(option, start, start + duration, intent), path.score + option.score.overall - continuity - repeatedShot)
                }
            }.sortedByDescending { it.score }.take(12)
        }
        val best = if (desiredClips == 1) shared ?: paths.maxByOrNull { it.score }
            else paths.maxByOrNull { it.score } ?: shared
        best ?: return null
        var cursor = 0L
        val clips = mutableListOf<ReelClipSegment>()
        best.picks.forEach { pick ->
            val last = clips.lastOrNull()
            if (last != null && last.source.uri == pick.option.source.uri && last.trimEndMs == pick.start) {
                clips[clips.lastIndex] = last.copy(trimEndMs = pick.end)
            } else clips.add(ReelClipSegment(pick.option.source, pick.start, pick.end, cursor))
            cursor += pick.end - pick.start
        }
        if (clips.size > 5) return null
        cursor = 0L
        val beats = candidate.beats.mapIndexed { i, text -> TextBeat(text, cursor, cursor + durations[i]).also { cursor += durations[i] } }
        val debug = JSONArray(best.picks.map { pick -> JSONObject().apply {
            put("text", pick.intent.text); put("intent", JSONArray(pick.intent.subjects.toList())); put("role", pick.intent.role)
            put("desiredEnergy", pick.intent.desiredEnergy); put("source", pick.option.source.uri); put("startMs", pick.start); put("endMs", pick.end)
            put("semantic", pick.option.score.semantic); put("vibe", pick.option.score.vibe); put("motion", pick.option.score.motion)
            put("concept", pick.option.score.concept); put("reusePenalty", pick.option.score.reusePenalty); put("overall", pick.option.score.overall)
            put("analysis", analyses[pick.option.source.uri]?.provider); put("crop", "center; suitability=${pick.option.section.crop.suitability}")
        } })
        val mean = (best.score / best.picks.size).coerceIn(0.0, 1.0)
        return VisualPlanResult(ReelPlan(request.id, request.category, request.humor, candidate.concept,
            intents.last().desiredMood, candidate.beats.first(), clips, beats,
            pacing = if (intents.map { it.desiredEnergy }.average() < .3) Pacing.CALM else Pacing.STEADY,
            payoff = candidate.beats.last(), qualityScore = mean,
            metadata = GenerationMetadata("visual-sequence-v1", request.seed, System.currentTimeMillis(),
                listOf("textSource=${candidate.source}", "matching=sampled-image-labels-and-vibe", "visualMatch=$debug") + listOfNotNull(candidate.itemId?.let { "contentItemId=$it" }))
        ).validated(), mean)
    }
}

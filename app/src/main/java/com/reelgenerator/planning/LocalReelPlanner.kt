package com.reelgenerator.planning

import com.reelgenerator.HumorStyle
import com.reelgenerator.PhaseTwoText
import com.reelgenerator.ReelCategory

data class PlanningRequest(
    val id: String,
    val category: ReelCategory,
    val humor: HumorStyle,
    val slot: Int,
    val seed: Int,
    val sources: List<SourceClip>,
    val forceSingle: Boolean = false,
    val captionOverride: String? = null,
    val fallbackReason: String? = null
)
fun interface ReelPlanner { fun plan(request: PlanningRequest): ReelPlan }

/** Phase 3A timeline fallback only: durations and reading time, not semantic understanding. */
class LocalReelPlanner : ReelPlanner {
    override fun plan(request: PlanningRequest): ReelPlan {
        require(request.sources.isNotEmpty()) { "No readable source clips are available." }
        val caption = request.captionOverride ?: PhaseTwoText.caption(request.category, request.humor, request.slot, request.seed)
        val lines = caption.lines().filter { it.isNotBlank() }
        val primary = request.sources.first()
        val multipleBeats = request.slot % 2 == 1 && lines.size > 1 && !request.forceSingle
        var texts = if (multipleBeats) lines else listOf(caption)
        var readingMs = texts.sumOf { ReadingDuration.minimumMs(it) }
        val desiredDuration = maxOf(8_000L, readingMs + 1_000).coerceAtMost(15_000)
        var wantedClips = if (request.forceSingle || request.slot < 2) 1 else if (request.slot == 2) 3 else 2
        if (!request.forceSingle && primary.durationMs < readingMs) wantedClips = 5
        val chosen = request.sources.distinctBy { it.id }.take(wantedClips).toMutableList()
        var duration = minOf(desiredDuration, chosen.sumOf { it.durationMs })
        if (duration < readingMs && multipleBeats) {
            texts = listOf(caption)
            readingMs = ReadingDuration.minimumMs(caption)
        }
        require(duration >= readingMs) { "Available clips are too short to read this caption comfortably." }
        // Avoid gratuitous cuts. A duration under 6 s may preserve a short but readable source.
        while (chosen.size > 1 && duration / chosen.size < 2_000) {
            // Keep useful long footage when the library starts with several very short clips.
            val shortestAlternative = (1 until chosen.size).minBy { chosen[it].durationMs }
            chosen.removeAt(shortestAlternative)
        }
        duration = minOf(duration, chosen.sumOf { it.durationMs })
        require(duration >= readingMs) { "Not enough usable footage for readable text." }
        var remaining = duration
        var cursor = 0L
        val segments = chosen.mapIndexed { index, clip ->
            val laterCapacity = chosen.drop(index + 1).sumOf { it.durationMs }
            val allocation = maxOf(remaining / (chosen.size - index), remaining - laterCapacity).coerceAtMost(clip.durationMs)
            val slack = clip.durationMs - allocation
            val trimStart = slack * Math.floorMod(request.seed.toLong() + request.slot + index, 4L) / 4
            ReelClipSegment(clip, trimStart, trimStart + allocation, cursor).also { cursor += allocation; remaining -= allocation }
        }.filter { it.durationMs > 0 }
        var textCursor = 0L
        val extra = duration - readingMs
        val beats = texts.mapIndexed { index, text ->
            // Short opening; let the payoff breathe. Text and cut times intentionally differ.
            val length = ReadingDuration.minimumMs(text) + if (index == texts.lastIndex) extra else 0
            TextBeat(text, textCursor, textCursor + length).also { textCursor += length }
        }
        return ReelPlan(request.id, request.category, request.humor,
            concept = caption.replace('\n', ' '), emotionalAngle = "Local category test concept",
            hook = texts.first(), clips = segments, textBeats = beats,
            pacing = if (segments.size > 2) Pacing.QUICK else Pacing.STEADY, payoff = texts.last(),
            metadata = GenerationMetadata("local-timeline-3a-v1", request.seed, System.currentTimeMillis(),
                listOf("Preset text; footage has not been semantically analyzed.", "Trim points are duration-based, not scene detection."), request.fallbackReason)
        ).validated()
    }

    companion object {
        /** Keeps pending Phase 2 jobs and the original single-clip API readable after upgrade. */
        fun legacy(id: String, category: ReelCategory, clip: SourceClip, caption: String): ReelPlan {
            val duration = minOf(clip.durationMs, 12_000L)
            return ReelPlan(id, category, HumorStyle.AUTO, caption, "Legacy preset", caption,
                listOf(ReelClipSegment(clip, 0, duration, 0)), listOf(TextBeat(caption, 0, duration)), payoff = caption,
                metadata = GenerationMetadata("legacy-single-clip", 0, System.currentTimeMillis())).validated()
        }
    }
}

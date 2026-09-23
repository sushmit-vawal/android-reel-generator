package com.reelgenerator.planning

import com.reelgenerator.HumorStyle
import com.reelgenerator.ReelCategory
import kotlin.math.ceil

data class SourceClip(val id: String, val uri: String, val durationMs: Long, val width: Int, val height: Int)
data class ReelClipSegment(val source: SourceClip, val trimStartMs: Long, val trimEndMs: Long, val outputStartMs: Long) {
    val durationMs get() = trimEndMs - trimStartMs
    val outputEndMs get() = outputStartMs + durationMs
}
data class TextBeat(val text: String, val startMs: Long, val endMs: Long, val emphasis: List<String> = emptyList())
enum class TypographyPreset { CLEAN }
data class TextStyle(val preset: TypographyPreset = TypographyPreset.CLEAN, val fontSizePx: Float = 64f)
enum class Pacing { CALM, STEADY, QUICK }
data class GenerationMetadata(
    val plannerVersion: String,
    val seed: Int,
    val createdAtMs: Long,
    val notes: List<String> = emptyList(),
    val fallbackReason: String? = null
)
data class ReelPlan(
    val id: String,
    val category: ReelCategory,
    val humorStyle: HumorStyle,
    val concept: String,
    val emotionalAngle: String,
    val hook: String,
    val clips: List<ReelClipSegment>,
    val textBeats: List<TextBeat>,
    val textStyle: TextStyle = TextStyle(),
    val pacing: Pacing = Pacing.STEADY,
    val payoff: String,
    val qualityScore: Double? = null,
    val metadata: GenerationMetadata,
    val version: Int = 1
) {
    val durationMs get() = clips.lastOrNull()?.outputEndMs ?: 0L
    fun beatAt(timeMs: Long): TextBeat? = textBeats.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }
    fun validated(): ReelPlan = apply {
        require(version == 1) { "Unsupported reel plan version." }
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid reel identifier." }
        require(clips.size in 1..5 && textBeats.size in 1..6) { "A plan needs 1–5 clips and 1–6 text beats." }
        var cursor = 0L
        clips.forEach {
            require(it.source.uri.startsWith("content://") || it.source.uri.startsWith("file://")) { "A source must be a local video URI." }
            require(it.source.durationMs in 1..604_800_000L && it.source.width > 0 && it.source.height > 0) { "Invalid source metadata." }
            require(it.trimStartMs >= 0 && it.trimEndMs > it.trimStartMs && it.trimEndMs <= it.source.durationMs) { "A trim is outside its source video." }
            require(it.durationMs <= 20_000 && it.outputStartMs == cursor) { "Clip segments must form one contiguous timeline." }
            cursor = it.outputEndMs
        }
        require(cursor in 1_000..20_000) { "Reel duration must be between 1 and 20 seconds." }
        var previousEnd = 0L
        textBeats.forEach {
            require(it.text.isNotBlank() && it.text.length <= 180) { "Text is empty or too long." }
            require(it.startMs >= previousEnd && it.endMs > it.startMs && it.endMs <= cursor) { "Text timing is overlapping or outside the reel." }
            require(it.emphasis.size <= 2 && it.emphasis.all { phrase -> phrase.isNotBlank() && phrase in it.text }) { "Emphasis must refer to text in the beat." }
            previousEnd = it.endMs
        }
        require(textStyle.fontSizePx.isFinite() && textStyle.fontSizePx in 40f..80f) { "Invalid text size." }
        require(qualityScore == null || (qualityScore.isFinite() && qualityScore in 0.0..1.0)) { "Invalid quality score." }
    }
}

object ReadingDuration {
    fun minimumMs(text: String, fontSizePx: Float = 64f, visualComplexity: Double = 0.5): Long {
        require(fontSizePx.isFinite() && fontSizePx > 0 && visualComplexity in 0.0..1.0)
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val base = 450 + words * 230 + text.length * 15
        val sizeFactor = (64.0 / fontSizePx).coerceIn(0.85, 1.3)
        return maxOf(1_600L, ceil(base * sizeFactor * (1 + visualComplexity * 0.2)).toLong())
    }
}

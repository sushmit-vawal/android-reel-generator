package com.reelgenerator.planning

import com.reelgenerator.ReelCategory

data class ReelBrief(
    val rawUserPrompt: String,
    val requestedCategory: ReelCategory = ReelCategory.TRAVEL,
    val requestedVisualConcepts: List<String> = emptyList(),
    val excludedVisualConcepts: List<String> = emptyList(),
    val requestedText: List<String> = emptyList(),
    val exactTextRequired: Boolean = false,
    val requestedOrder: List<String> = emptyList(),
    val requestedMood: String = "natural",
    val requestedPacing: String = "steady",
    val requestedClipCount: Int? = null,
    val requestedDurationMs: Long? = null,
    val requestedTypographyPreset: TypographyPreset? = null,
    val trendInfluenceEnabled: Boolean = true
)

object ReelBriefParser {
    fun parse(prompt: String): ReelBrief {
        val lower = prompt.lowercase()
        val category = when { "funny" in lower || "humor" in lower -> ReelCategory.HUMOR; "workout" in lower || "motivat" in lower -> ReelCategory.MOTIVATION; "lifestyle" in lower -> ReelCategory.LIFESTYLE; else -> ReelCategory.TRAVEL }
        val vocabulary = listOf("sunrise", "sunset", "office", "laptop", "airport", "airplane", "ocean", "beach", "workout", "swimming", "mountain", "road", "coffee", "city")
        val concepts = vocabulary.filter { it in lower }
        val exact = Regex("""(?is)(?:use|with)\s+(?:my\s+)?exact\s+text\s*:\s*(.+)""").find(prompt)?.groupValues?.get(1)?.trim()
        val text = exact?.split("||")?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        val mood = listOf("cinematic", "calm", "energetic", "funny", "emotional", "nostalgic", "dramatic", "minimal", "motivational").firstOrNull { it in lower } ?: "natural"
        val pacing = if ("slow" in lower || "calm" in lower) "calm" else if ("fast" in lower || "energetic" in lower) "quick" else "steady"
        return ReelBrief(prompt, category, concepts, emptyList(), text, exact != null, concepts, mood, pacing, trendInfluenceEnabled = true)
    }
}

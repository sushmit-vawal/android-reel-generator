package com.reelgenerator.content

import com.reelgenerator.*
import com.reelgenerator.data.*
import com.reelgenerator.planning.*
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

data class TextCandidate(val beats: List<String>, val concept: String, val source: String, val itemId: String? = null, val tags: String = "", val preferredClips: Int? = null)

/** A real model adapter may supply candidates here. No model is bundled with this build. */
fun interface TextGenerator { suspend fun generate(category: ReelCategory, humor: HumorStyle, seed: Int): List<TextCandidate> }

object TextIdentity {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace("||", " ").replace(Regex("[\\p{P}\\p{Z}\\s]+"), " ").trim()
    fun similarity(a: String, b: String): Double {
        val x = normalize(a).split(' ').filter(String::isNotBlank).toSet()
        val y = normalize(b).split(' ').filter(String::isNotBlank).toSet()
        return x.intersect(y).size.toDouble() / x.union(y).size.coerceAtLeast(1)
    }
}

/** Filename keyword matching is a local fallback, not visual recognition or embeddings. */
class ContentCandidatePlanner(private val generator: TextGenerator = TextGenerator { _, _, _ -> emptyList() }) {
    suspend fun select(request: PlanningRequest, items: List<ContentLibraryItem>, videos: List<SourceVideo>, history: List<String>, batchUsage: Map<String, Int>): ReelPlan {
        val imported = items.filter { it.enabled && (it.category.equals(request.category.name, true) || it.category.equals("Uncategorized", true) || it.category.isBlank()) }
            .sortedWith(compareBy<ContentLibraryItem> { it.useCount }.thenBy { it.lastUsedAt }).mapNotNull { item ->
                try {
                    val array = JSONArray(item.beatsJson)
                    TextCandidate((0 until array.length()).map(array::getString), item.subTheme.ifBlank { item.category }, item.sourceType, item.id, item.tags, item.preferredClipCount)
                } catch (_: Exception) { null }
            }
        val ai = try { generator.generate(request.category, request.humor, request.seed).filter { it.source == "AI_GENERATED" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        val fallback = (0..4).map { TextCandidate(listOf(PhaseTwoText.caption(request.category, request.humor, it, request.seed)), request.category.label, "BUILT_IN_FALLBACK") }
        val normalizedHistory = history.map(TextIdentity::normalize).toSet()
        val candidates = (0 until maxOf(imported.size, ai.size, fallback.size)).asSequence()
            .flatMap { index -> listOfNotNull(imported.getOrNull(index), ai.getOrNull(index), fallback.getOrNull(index)) }
            .distinctBy { TextIdentity.normalize(it.beats.joinToString(" ")) }
        val plans = candidates.mapNotNull { candidate ->
            val text = candidate.beats.joinToString(" ")
            val identity = TextIdentity.normalize(text)
            val similarity = history.maxOfOrNull { TextIdentity.similarity(text, it) } ?: 0.0
            if (identity in normalizedHistory || similarity >= .90) return@mapNotNull null
            val intent = keywords(text + " " + candidate.tags + " " + candidate.concept)
            val matches = request.sources.map { clip ->
                val video = videos.find { it.uri == clip.uri }
                val labels = keywords(video?.name.orEmpty())
                val relevance = intent.intersect(labels).size
                Triple(clip, relevance, (batchUsage[clip.uri] ?: 0) * 2.0 + (video?.useCount ?: 0) * .15)
            }
            // With specific visual words, require at least one evidenced filename match.
            val eligible = if (candidate.itemId != null && intent.isNotEmpty()) matches.filter { it.second > 0 } else matches
            if (eligible.isEmpty()) return@mapNotNull null
            val ordered = eligible.sortedByDescending { it.second * 3.0 - it.third }.map { it.first }
            try {
                val plan = LocalReelPlanner().plan(request.copy(sources = ordered, captionOverride = candidate.beats.joinToString("\n"), scriptBeats = candidate.beats,
                    preferredClipCount = candidate.preferredClips ?: if (ordered.first().durationMs >= candidate.beats.sumOf { ReadingDuration.minimumMs(it) } + 1000) 1 else 3))
                val notes = listOf("textSource=${candidate.source}", "matching=filename-keywords; no visual semantic model", "textSimilarity=token-overlap:$similarity") + listOfNotNull(candidate.itemId?.let { "contentItemId=$it" })
                (plan.copy(concept = candidate.concept, metadata = plan.metadata.copy(plannerVersion = "content-candidates-v1", notes = notes))) to
                    ((if (candidate.itemId != null) 2.0 else 1.0) + eligible.maxOf { it.second } - similarity * 3)
            } catch (_: IllegalArgumentException) { null }
        }.take(12)
        return plans.maxByOrNull { it.second }?.first ?: error("No fresh readable content matches the available footage. Import more text or add suitably named footage.")
    }

    private fun keywords(text: String): Set<String> {
        val words = TextIdentity.normalize(text).split(Regex("[^\\p{L}]+"))
        val groups = listOf(setOf("beach", "ocean", "sea", "scuba"), setOf("airport", "flight", "flying", "airplane"), setOf("office", "laptop", "work", "mondays"), setOf("gym", "workout", "running", "fitness"), setOf("sunrise", "morning", "mornings"), setOf("mountain", "hiking", "hike"), setOf("road", "driving", "car"), setOf("home", "house"), setOf("coffee", "cafe"))
        return groups.filter { group -> words.any { it in group } }.map { it.first() }.toSet()
    }
}

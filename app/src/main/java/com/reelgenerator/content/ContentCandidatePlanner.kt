package com.reelgenerator.content

import com.reelgenerator.*
import com.reelgenerator.analysis.*
import com.reelgenerator.data.*
import com.reelgenerator.planning.*
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

data class TextCandidate(val beats: List<String>, val concept: String, val source: String, val itemId: String? = null, val tags: String = "", val preferredClips: Int? = null)
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

class NoVisualMatchException(message: String) : IllegalStateException(message)

class ContentCandidatePlanner(private val generator: TextGenerator = TextGenerator { _, _, _ -> emptyList() }, private val policy: MatchPolicy = MatchPolicy()) {
    val diagnostics = mutableListOf<String>()

    suspend fun candidates(request: PlanningRequest, items: List<ContentLibraryItem>, videos: List<SourceVideo>, history: List<String>, batchUsage: Map<String, Int>,
                           analyses: Map<String, ClipAnalysis>, pairings: List<ReelPairingHistory>, limit: Int = 20): List<VisualPlanResult> {
        diagnostics.clear()
        val library = items.filter { it.enabled && (it.category.equals(request.category.name, true) || it.category.equals("Uncategorized", true) || it.category.isBlank()) }
            .mapNotNull { item -> try {
                val array = JSONArray(item.beatsJson)
                TextCandidate((0 until array.length()).map(array::getString), item.subTheme.ifBlank { item.category }, item.sourceType, item.id, item.tags, item.preferredClipCount)
            } catch (_: Exception) { null } }
        val normalizedHistory = history.map(TextIdentity::normalize).toSet()
        val matcher = VisualPlanMatcher(policy)
        suspend fun evaluate(input: List<TextCandidate>, allowHistorical: Boolean, sourcePenalty: Double = 0.0): List<VisualPlanResult> {
            val out = mutableListOf<VisualPlanResult>()
            input.distinctBy { TextIdentity.normalize(it.beats.joinToString(" ")) }.forEach { candidate ->
                currentCoroutineContext().ensureActive()
                val text = candidate.beats.joinToString(" ")
                val identity = TextIdentity.normalize(text)
                val historical = identity in normalizedHistory || history.any { TextIdentity.similarity(text, it) >= .96 }
                if (historical && !allowHistorical) return@forEach
                val result = matcher.match(request, candidate, analyses, videos, batchUsage, pairings, diagnostics) ?: return@forEach
                if (!isVisuallyGrounded(candidate, result.plan, request, analyses)) {
                    diagnostics += "rejectedUngrounded=${candidate.beats.joinToString(" | ")}"
                    return@forEach
                }
                if (historical && candidate.itemId != null && !usesNewInterpretation(result.plan, identity, pairings)) return@forEach
                out += result.copy(score = result.score - sourcePenalty - if (historical) .06 else 0.0)
            }
            return out
        }
        val freshImported = evaluate(library, false)
        val reusedImported = evaluate(library, true).filter { result ->
            result.plan.metadata.notes.any { it.startsWith("contentItemId=") } && freshImported.none { it.plan.textBeats.map(TextBeat::text) == result.plan.textBeats.map(TextBeat::text) }
        }
        val ai = try { generator.generate(request.category, request.humor, request.seed).filter { it.source == "AI_GENERATED" } }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        val groups = listOf(
            freshImported,
            reusedImported,
            evaluate(ai, false, .01),
            evaluate(DynamicLocalTextGenerator.candidates(request.category, analyses.values, request.seed), false, .02),
            evaluate(VideoFirstTemplates.candidates(request.category, analyses.values.toList()), false, .03),
            evaluate(EmergencyText.candidates(request.category, request.seed), false, .05),
            evaluate((0..4).map { TextCandidate(listOf(PhaseTwoText.caption(request.category, request.humor, it, request.seed)), request.category.label, "LEGACY_EMERGENCY") }, true, .09)
        )
        diagnostics += "candidateCounts=${groups.map { it.size }}"
        // The pipeline order is intentional. A large tier bonus keeps a visually valid
        // imported row ahead of generated material while relevance still ranks peers inside
        // the same tier. Later tiers only fill the pool when earlier ones are insufficient.
        return groups.flatMapIndexed { tier, results ->
            results.sortedByDescending(VisualPlanResult::score).map { it.copy(score = it.score + (groups.size - tier) * 2.0) }
        }
            .distinctBy { TextIdentity.normalize(it.plan.textBeats.joinToString(" ") { beat -> beat.text }) }.take(limit)
    }

    suspend fun select(request: PlanningRequest, items: List<ContentLibraryItem>, videos: List<SourceVideo>, history: List<String>, batchUsage: Map<String, Int>,
                       analyses: Map<String, ClipAnalysis> = emptyMap(), pairings: List<ReelPairingHistory> = emptyList()): ReelPlan {
        val plans = candidates(request, items, videos, history, batchUsage, analyses, pairings)
        if (plans.isEmpty()) throw NoVisualMatchException("No readable visual/text plan fits ${analyses.size} indexed videos. The batch will continue with another source or local creative fallback.")
        val top = plans.maxOf { it.score }
        return plans.filter { it.score >= top - .035 }.random(Random(request.seed xor (request.slot * 7919))).plan.also { diagnostics.addAll(it.metadata.notes) }
    }

    private fun usesNewInterpretation(plan: ReelPlan, normalized: String, pairings: List<ReelPairingHistory>) =
        plan.clips.all { clip -> pairings.none { old -> old.normalizedText == normalized && old.sourceUri == clip.source.uri && old.sourceStartMs < clip.trimEndMs && old.sourceEndMs > clip.trimStartMs } }

    private fun isVisuallyGrounded(candidate: TextCandidate, plan: ReelPlan, request: PlanningRequest, analyses: Map<String, ClipAnalysis>): Boolean {
        val literalSubjects = VisualVocabulary.intents(candidate.beats, candidate.tags, candidate.concept, request.category)
            .flatMap { it.subjects }.toSet()
        if (literalSubjects.isEmpty()) return true
        val observed = plan.clips.flatMap { clip -> analyses[clip.source.uri]?.temporalSegments.orEmpty() }
            .flatMap { VisualVocabulary.canonical(it.semanticTags).filterValues { confidence -> confidence >= .45 }.keys }.toSet()
        return literalSubjects.any { it in observed }
    }
}

object EmergencyText {
    private val lines = mapOf(
        ReelCategory.TRAVEL to listOf("A different view changes the day", "Keep the part that felt alive", "The long way can still be worth it", "One scene can change the whole story", "Save a little room for somewhere new", "Let the destination arrive slowly", "The best part was never on the schedule", "Take the memory, leave the rush"),
        ReelCategory.MOTIVATION to listOf("Start before the mood arrives", "Quiet work still moves forward", "One honest repetition is progress", "Keep the promise you made yourself", "The result begins with the routine", "Small effort is still evidence", "Show up imperfectly and continue", "Progress does not need an audience"),
        ReelCategory.LIFESTYLE to listOf("Keep the ordinary parts too", "A slower moment still counts", "Make room for the small good things", "The simple version worked today", "Not every memory needs an occasion", "A quiet routine can be enough", "This part of the day was mine", "Notice what usually passes by"),
        ReelCategory.HUMOR to listOf("The confidence was slightly premature", "Technically there was a plan", "The idea survived longer than expected", "Experience was gained against my will", "Everything worked except the important part", "The instructions felt optional", "I would like to blame the lighting", "A professional would have noticed sooner")
    )
    fun candidates(category: ReelCategory, seed: Int) = lines.getValue(category).map { text ->
        TextCandidate(listOf(text), category.label, "EXPANDED_EMERGENCY", tags = if (category == ReelCategory.MOTIVATION) "work|fitness" else "")
    }.shuffled(Random(seed))
}

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
/** Optional real text model adapter. Shipping build uses CSV and explicitly named templates. */
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
    suspend fun select(request: PlanningRequest, items: List<ContentLibraryItem>, videos: List<SourceVideo>, history: List<String>, batchUsage: Map<String, Int>,
                       analyses: Map<String, ClipAnalysis> = emptyMap(), pairings: List<ReelPairingHistory> = emptyList()): ReelPlan {
        diagnostics.clear()
        val imported = items.filter { it.enabled && (it.category.equals(request.category.name, true) || it.category.equals("Uncategorized", true) || it.category.isBlank()) }
            .mapNotNull { item -> try {
                val array = JSONArray(item.beatsJson)
                TextCandidate((0 until array.length()).map(array::getString), item.subTheme.ifBlank { item.category }, item.sourceType, item.id, item.tags, item.preferredClipCount)
            } catch (_: Exception) { null } }
        val ai = try { generator.generate(request.category, request.humor, request.seed).filter { it.source == "AI_GENERATED" } }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        val normalizedHistory = history.map(TextIdentity::normalize).toSet()
        var used = 0; var deferred = 0
        val matcher = VisualPlanMatcher(policy)
        suspend fun evaluate(candidates: List<TextCandidate>): List<VisualPlanResult> {
            val plans = mutableListOf<VisualPlanResult>()
            candidates.distinctBy { TextIdentity.normalize(it.beats.joinToString(" ")) }.forEach { candidate ->
                currentCoroutineContext().ensureActive()
                val text = candidate.beats.joinToString(" ")
                if (TextIdentity.normalize(text) in normalizedHistory || history.any { TextIdentity.similarity(text, it) >= .96 }) { used++; return@forEach }
                val plan = matcher.match(request, candidate, analyses, videos, batchUsage, pairings, diagnostics)
                if (plan != null) plans.add(plan) else deferred++
            }
            return plans
        }
        var plans = evaluate(imported)
        val importedUsed = used
        val importedDeferred = deferred
        if (plans.isEmpty()) plans = evaluate(ai)
        if (plans.isEmpty()) plans = evaluate((0..4).map { TextCandidate(listOf(PhaseTwoText.caption(request.category, request.humor, it, request.seed)), request.category.label, "BUILT_IN_FALLBACK") })
        if (plans.isEmpty()) plans = evaluate(VideoFirstTemplates.candidates(request.category, analyses.values.toList()))
        if (plans.isEmpty()) throw NoVisualMatchException("${imported.size} library rows in this category: $importedUsed already used, $importedDeferred need suitable footage or shorter text. No fresh fallback fits. ${analyses.size} videos analyzed. Try another category or review row lengths/tags in Content Library.")
        val top = plans.maxOf { it.score }
        return plans.filter { it.score >= top - .035 }.random(Random(request.seed xor (request.slot * 7919))).plan.also { diagnostics.addAll(it.metadata.notes) }
    }
}

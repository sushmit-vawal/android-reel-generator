package com.reelgenerator.planning

import com.reelgenerator.content.TextIdentity

data class BatchPlanSelection(val selected: List<VisualPlanResult>, val reserves: Map<Int, List<VisualPlanResult>>, val score: Double)

/** Selects the five outputs jointly. Relevance remains dominant; close plans gain meaningful
 * value from different sources, concepts, text pairings, and temporal ranges. */
object BatchPlanSelector {
    private data class State(val plans: List<VisualPlanResult>, val score: Double)
    fun select(pools: List<List<VisualPlanResult>>, target: Int = 5): BatchPlanSelection {
        val distinctPrimaries = pools.flatten().mapNotNull { it.plan.clips.firstOrNull()?.source?.uri }.toSet().size
        val requireUniquePrimary = distinctPrimaries >= target
        var states = listOf(State(emptyList(), 0.0))
        pools.take(target).forEach { pool ->
            states = states.flatMap { state -> pool.take(20).mapNotNull { candidate ->
                val identity = TextIdentity.normalize(candidate.plan.textBeats.joinToString(" ") { it.text })
                if (state.plans.any { TextIdentity.normalize(it.plan.textBeats.joinToString(" ") { beat -> beat.text }) == identity }) return@mapNotNull null
                if (state.plans.any { TextIdentity.similarity(it.plan.textBeats.joinToString(" ") { beat -> beat.text }, candidate.plan.textBeats.joinToString(" ") { beat -> beat.text }) >= .82 }) return@mapNotNull null
                val sources = candidate.plan.clips.map { it.source.uri }.toSet()
                val priorSources = state.plans.flatMap { it.plan.clips }.map { it.source.uri }
                val primary = candidate.plan.clips.first().source.uri
                if (requireUniquePrimary && state.plans.any { it.plan.clips.first().source.uri == primary }) return@mapNotNull null
                val repeatedSources = sources.sumOf { source -> priorSources.count { it == source } }
                val sameConcept = state.plans.count { it.plan.concept.equals(candidate.plan.concept, true) }
                val sameRanges = candidate.plan.clips.sumOf { clip -> state.plans.flatMap { it.plan.clips }.count {
                    it.source.uri == clip.source.uri && it.trimStartMs < clip.trimEndMs && it.trimEndMs > clip.trimStartMs
                } }
                val diversity = sources.count { it !in priorSources } * .035
                State(state.plans + candidate, state.score + candidate.score + diversity - repeatedSources * .35 - sameConcept * .12 - sameRanges * .18)
            } }.sortedByDescending(State::score).take(64)
        }
        val best = states.maxByOrNull(State::score) ?: State(emptyList(), 0.0)
        val reserves = pools.take(target).mapIndexed { index, pool -> index to pool.filterNot { candidate ->
            best.plans.any { it.plan.id == candidate.plan.id && it.plan.clips == candidate.plan.clips && it.plan.textBeats == candidate.plan.textBeats }
        }.sortedByDescending(VisualPlanResult::score) }.toMap()
        return BatchPlanSelection(best.plans, reserves, best.score)
    }
}

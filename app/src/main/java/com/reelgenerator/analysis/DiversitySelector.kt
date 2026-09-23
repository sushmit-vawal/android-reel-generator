package com.reelgenerator.analysis

data class DiversityFeatures(val concept: String, val hook: String, val clipIds: Set<String>, val structure: String)
data class RankedCandidate<T>(val value: T, val quality: Float, val features: DiversityFeatures)

/** Greedy batch selector: quality leads, then novelty against already selected plans. */
class DiversitySelector {
    fun <T> select(candidates: List<RankedCandidate<T>>, count: Int): List<T> {
        val remaining = candidates.toMutableList(); val selected = mutableListOf<RankedCandidate<T>>()
        repeat(count.coerceAtMost(candidates.size)) {
            val next = remaining.maxByOrNull { candidate -> candidate.quality + selected.sumOf { novelty(candidate.features, it.features).toDouble() }.toFloat() * .25f } ?: return@repeat
            selected += next; remaining -= next
        }
        return selected.map { it.value }
    }
    private fun novelty(a: DiversityFeatures, b: DiversityFeatures): Float {
        val concept = if (a.concept.equals(b.concept, true)) 0f else 1f
        val hook = if (a.hook.equals(b.hook, true)) 0f else 1f
        val clips = if (a.clipIds.isEmpty() && b.clipIds.isEmpty()) 0f else 1f - a.clipIds.intersect(b.clipIds).size.toFloat() / a.clipIds.union(b.clipIds).size.coerceAtLeast(1)
        val structure = if (a.structure == b.structure) 0f else 1f
        return (concept + hook + clips + structure) / 4f
    }
}

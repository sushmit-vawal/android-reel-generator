package com.reelgenerator.analysis

data class CandidateEvaluation(val candidate: CreativeCandidate, val readability: Float, val distinctiveness: Float, val hookStrength: Float, val total: Float)

class CandidateEvaluator {
    fun evaluate(candidates: List<CreativeCandidate>): List<CandidateEvaluation> = candidates.map { candidate ->
        val words = candidate.text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val readability = (1f - ((words.size - 12).coerceAtLeast(0) / 24f)).coerceIn(0f, 1f)
        val hookStrength = if (candidate.hook.length in 8..72) 1f else .5f
        val distinctiveness = (candidate.text.lowercase().toSet().size / 26f).coerceIn(0f, 1f)
        CandidateEvaluation(candidate, readability, distinctiveness, hookStrength, readability * .45f + distinctiveness * .2f + hookStrength * .35f)
    }.sortedByDescending(CandidateEvaluation::total)
}

class HookEngine {
    fun select(evaluations: List<CandidateEvaluation>): CreativeCandidate? = evaluations.maxByOrNull(CandidateEvaluation::total)?.candidate
}

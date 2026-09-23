package com.reelgenerator.analysis

import com.reelgenerator.HumorStyle
import com.reelgenerator.ReelCategory
import com.reelgenerator.PhaseTwoText

data class CreativeCandidate(val text: String, val hook: String, val score: Float?, val source: String)

/** Deterministic candidate generation. AI/provider-backed candidates can be added in a later adapter. */
class CreativeCandidateEngine {
    fun generate(category: ReelCategory, humor: HumorStyle, slot: Int, seed: Int): List<CreativeCandidate> {
        val text = PhaseTwoText.caption(category, humor, slot, seed)
        return listOf(CreativeCandidate(text, text.lineSequence().first(), null, "local-preset"))
    }

    fun deduplicate(candidates: List<CreativeCandidate>): List<CreativeCandidate> =
        candidates.distinctBy { it.text.trim().lowercase() }
}

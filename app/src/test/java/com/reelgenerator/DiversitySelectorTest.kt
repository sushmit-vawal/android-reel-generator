package com.reelgenerator

import com.reelgenerator.analysis.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DiversitySelectorTest {
    @Test fun qualityAndNoveltyBothInfluenceBatch() {
        val a = RankedCandidate("a", .95f, DiversityFeatures("travel", "statement", setOf("clip"), "single"))
        val b = RankedCandidate("b", .92f, DiversityFeatures("travel", "statement", setOf("clip"), "single"))
        val c = RankedCandidate("c", .80f, DiversityFeatures("nostalgia", "question", setOf("other"), "beats"))
        assertEquals(listOf("a", "c"), DiversitySelector().select(listOf(a, b, c), 2))
    }
}

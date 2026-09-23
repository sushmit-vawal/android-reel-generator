package com.reelgenerator

import com.reelgenerator.analysis.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisTest {
    @Test fun candidatesDeduplicateAndHookEngineRanksReadability() {
        val engine = CreativeCandidateEngine()
        val candidates = engine.deduplicate(listOf(
            CreativeCandidate("A clear hook", "A clear hook", null, "test"),
            CreativeCandidate("a clear hook", "a clear hook", null, "test"),
            CreativeCandidate("A longer candidate that still remains readable", "A longer candidate", null, "test")
        ))
        assertEquals(2, candidates.size)
        assertNotNull(HookEngine().select(CandidateEvaluator().evaluate(candidates)))
    }
}

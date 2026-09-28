package com.reelgenerator

import com.reelgenerator.planning.*
import org.junit.Assert.*
import org.junit.Test

class ReelBriefTest {
    @Test fun parsesVisualOrderMoodAndExactBeats() {
        val brief = ReelBriefParser.parse("Start with office/laptop, then airport, then ocean. Make it cinematic. Use my exact text: Work first. || Then travel.")
        assertEquals(listOf("office", "laptop", "airport", "ocean"), brief.requestedVisualConcepts)
        assertEquals(listOf("Work first.", "Then travel."), brief.requestedText)
        assertTrue(brief.exactTextRequired); assertEquals("cinematic", brief.requestedMood)
        assertFalse(brief.trendInfluenceEnabled)
    }
    @Test fun noTextPromptRemainsVideoFirstAndGeneratable() {
        val brief = ReelBriefParser.parse("Make a motivational reel using sunrise and beach workout clips")
        assertTrue(brief.requestedText.isEmpty()); assertEquals(ReelCategory.MOTIVATION, brief.requestedCategory)
        assertEquals(listOf("sunrise", "beach", "workout"), brief.requestedVisualConcepts)
    }
}

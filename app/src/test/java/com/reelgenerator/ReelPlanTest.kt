package com.reelgenerator

import com.reelgenerator.planning.*
import org.junit.Assert.*
import org.junit.Test

class ReelPlanTest {
    private val clips = (0..4).map { SourceClip("clip$it", "content://test/$it", 30_000, 1920, 1080) }
    private fun plan(slot: Int = 0, sources: List<SourceClip> = clips) = LocalReelPlanner().plan(PlanningRequest("test_$slot", ReelCategory.TRAVEL, HumorStyle.AUTO, slot, 7, sources))
    @Test fun supportsAllFourStructuresWithoutCouplingTextToCuts() {
        val plans = (0..4).map { plan(it) }
        assertEquals(1, plans[0].clips.size); assertEquals(1, plans[0].textBeats.size)
        assertEquals(1, plans[1].clips.size); assertTrue(plans[1].textBeats.size > 1)
        assertTrue(plans[2].clips.size > 1); assertEquals(1, plans[2].textBeats.size)
        assertTrue(plans[3].clips.size > 1); assertTrue(plans[3].textBeats.size > 1)
        assertEquals(plans[2].durationMs, plans[2].textBeats.single().endMs)
        assertTrue(plans[3].textBeats.first().endMs != plans[3].clips.first().outputEndMs)
    }
    @Test fun trimsStayWithinSourcesAndTimelineIsContiguous() {
        for (slot in 0..4) {
            val plan = plan(slot)
            assertTrue(plan.durationMs in 6_000..20_000)
            assertEquals(plan.durationMs, plan.clips.sumOf { it.durationMs })
            assertEquals(plan.clips.size, plan.clips.map { it.source.id }.distinct().size)
            plan.textBeats.forEach { assertTrue(it.endMs - it.startMs >= ReadingDuration.minimumMs(it.text)) }
            plan.clips.forEach { assertTrue(it.trimStartMs >= 0 && it.trimEndMs <= it.source.durationMs) }
        }
        assertTrue((0..4).flatMap { plan(it).clips }.any { it.trimStartMs > 0 })
    }
    @Test fun oneSourceStillSupportsSingleAndMultipleBeats() {
        for (slot in 0..4) assertEquals(1, plan(slot, clips.take(1)).clips.size)
        assertTrue(plan(1, clips.take(1)).textBeats.size > 1)
    }
    @Test fun canCombineShortClipsToMakeReadableTimeline() {
        val plan = plan(3, clips.map { it.copy(durationMs = 2_000) })
        assertTrue(plan.clips.size > 1)
        assertTrue(plan.durationMs >= plan.textBeats.sumOf { ReadingDuration.minimumMs(it.text) })
    }
    @Test fun retainsLongAlternativeWhenFirstClipsAreVeryShort() {
        val sources = clips.mapIndexed { index, clip -> if (index < 4) clip.copy(durationMs = 1_000) else clip }
        val result = plan(3, sources)
        assertTrue(result.clips.any { it.source.id == clips.last().id })
        assertTrue(result.durationMs >= result.textBeats.sumOf { ReadingDuration.minimumMs(it.text) })
    }
    @Test fun fallbackRetainsCategoryAndOneClip() {
        val plan = LocalReelPlanner().plan(PlanningRequest("fallback", ReelCategory.HUMOR, HumorStyle.DARK, 3, 1, clips, true, fallbackReason = "codec"))
        assertEquals(1, plan.clips.size); assertEquals(ReelCategory.HUMOR, plan.category)
        assertEquals("codec", plan.metadata.fallbackReason)
    }
    @Test fun legacyPathRetainsShortSourceAndCaption() {
        val plan = LocalReelPlanner.legacy("legacy", ReelCategory.TRAVEL, clips.first().copy(durationMs = 1_000), "Old caption")
        assertEquals(1_000L, plan.durationMs); assertEquals("Old caption", plan.textBeats.single().text)
    }
    @Test fun textUsesHalfOpenIntervalsIncludingGaps() {
        val plan = plan().copy(textBeats = listOf(TextBeat("One", 0, 2_000), TextBeat("Two", 3_000, 5_000))).validated()
        assertEquals("One", plan.beatAt(1_999)?.text)
        assertNull(plan.beatAt(2_000)); assertNull(plan.beatAt(2_999))
        assertEquals("Two", plan.beatAt(3_000)?.text); assertNull(plan.beatAt(5_000))
    }
    @Test fun rejectsInvalidTimelinesAndUnsafeIds() {
        val valid = plan()
        val bad = listOf(valid.copy(id = "../bad"), valid.copy(clips = emptyList()),
            valid.copy(clips = listOf(valid.clips.first().copy(trimStartMs = -1))),
            valid.copy(clips = listOf(valid.clips.first().copy(outputStartMs = 1))),
            valid.copy(textBeats = listOf(TextBeat("Late", 0, 99_000))),
            valid.copy(textBeats = listOf(TextBeat("A", 0, 3_000), TextBeat("B", 2_000, 4_000))))
        bad.forEach { assertTrue(runCatching { it.validated() }.isFailure) }
    }
    @Test fun readingDurationRespondsToTextAndComplexity() {
        assertTrue(ReadingDuration.minimumMs("An entire sentence takes more time to read.") > ReadingDuration.minimumMs("Hello"))
        assertTrue(ReadingDuration.minimumMs("This needs time", visualComplexity = 1.0) > ReadingDuration.minimumMs("This needs time", visualComplexity = 0.0))
        assertTrue(ReadingDuration.minimumMs("This needs time", 40f) > ReadingDuration.minimumMs("This needs time", 80f))
    }
}

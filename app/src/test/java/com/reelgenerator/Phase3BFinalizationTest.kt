package com.reelgenerator

import com.reelgenerator.analysis.*
import com.reelgenerator.content.*
import com.reelgenerator.planning.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Phase3BFinalizationTest {
    private fun clip(id: Int, tag: String = "ocean") = SourceClip("$id", "content://$id", 30_000, 1080, 1920) to
        ClipAnalysis("content://$id", "f$id", "test", listOf(ClipTemporalSegment(0, 30_000,
            listOf(SemanticTag(tag, .94)), VibeProfile(.3, .3, .5, .4, "calm", tag, "wide", .8), .9,
            CropAssessment(.4, .8, .5))), .9)

    @Test fun fivePlanSelectionPrefersFreshSourcesButKeepsRelevantReuseEligible() {
        fun result(slot: Int, source: SourceClip, score: Double, text: String) = VisualPlanResult(
            LocalReelPlanner.legacy("plan_${slot}_${text.hashCode()}", ReelCategory.TRAVEL, source, text), score)
        val clips = (0..5).map { clip(it).first }
        val pools = (0 until 5).map { slot -> listOf(
            result(slot, clips[0], .95, "Shared $slot"),
            result(slot, clips[slot + 1], .91, "Fresh $slot")
        ) }
        val selected = BatchPlanSelector.select(pools).selected
        assertEquals(5, selected.size)
        assertEquals(5, selected.flatMap { it.plan.clips }.map { it.source.uri }.toSet().size)
    }

    @Test fun exhaustedImportedTextContinuesWithUnboundedLocalCreativeCandidates() = runTest {
        val (source, evidence) = clip(1)
        val request = PlanningRequest("dynamic", ReelCategory.TRAVEL, HumorStyle.AUTO, 0, 77, listOf(source))
        val oldLegacy = (0..4).map { PhaseTwoText.caption(ReelCategory.TRAVEL, HumorStyle.AUTO, it, 77) }
        val results = ContentCandidatePlanner().candidates(request, emptyList(), emptyList(), oldLegacy,
            emptyMap(), mapOf(source.uri to evidence), emptyList(), 20)
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().plan.metadata.notes.contains("textSource=DYNAMIC_LOCAL"))
        assertTrue(DynamicLocalTextGenerator.candidates(ReelCategory.TRAVEL, listOf(evidence), 1).size > 20)
    }
}

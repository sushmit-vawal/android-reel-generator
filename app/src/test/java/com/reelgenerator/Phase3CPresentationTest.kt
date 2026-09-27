package com.reelgenerator

import com.reelgenerator.planning.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Phase3CPresentationTest {
    @Test fun safeAreaLeavesProtectedControlsAndMargins() {
        val safe = SafeAreaConfig()
        val bounds = safe.bounds(1440, 2560)
        assertTrue(bounds.left >= 140f); assertTrue(bounds.right <= 1185f)
        assertTrue(bounds.top >= 295f); assertTrue(bounds.bottom <= 2165f)
        assertTrue(safe.contentWidth(1440) < 1440)
    }

    @Test fun presetChoiceIsDeterministicAndVariesAcrossCompatibleBatchSlots() {
        val choices = (0..4).map { PresentationPlanner.choose(ReelCategory.TRAVEL, "A short caption", 44, it).preset }
        assertEquals(choices, (0..4).map { PresentationPlanner.choose(ReelCategory.TRAVEL, "A short caption", 44, it).preset })
        assertTrue(choices.distinct().size > 1)
        assertEquals(TypographyPreset.HUMOR, PresentationPlanner.choose(ReelCategory.HUMOR, "A joke", 1).preset)
    }

    @Test fun expandedPresentationRoundTripsAndLegacyStyleDefaults() {
        val base = LocalReelPlanner.legacy("presentation", ReelCategory.TRAVEL, SourceClip("x", "content://x", 12_000, 1080, 1920), "The view was worth it")
        val expanded = base.copy(version = 2, textStyle = TextStyle(TypographyPreset.CINEMATIC, 62f, treatment = TextTreatment.OUTLINE, placementY = .2f))
        val decoded = ReelPlanCodec.decode(ReelPlanCodec.encode(expanded))
        assertEquals(expanded.textStyle, decoded.textStyle)
        assertEquals(2, decoded.version)
    }
}

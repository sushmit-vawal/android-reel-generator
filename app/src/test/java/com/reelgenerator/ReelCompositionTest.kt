package com.reelgenerator

import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import com.reelgenerator.planning.*
import com.reelgenerator.rendering.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReelCompositionTest {
    private fun plan(): ReelPlan = LocalReelPlanner().plan(PlanningRequest("composition", ReelCategory.TRAVEL, HumorStyle.AUTO, 3, 4,
        listOf(SourceClip("one", "content://test/one", 15_000, 1920, 1080), SourceClip("two", "content://test/two", 20_000, 1080, 1920))))
    @Test fun serializedPlanRoundTripsExactly() {
        val plan = plan().copy(qualityScore = null, textBeats = plan().textBeats.map { it.copy(emphasis = listOf(it.text.split(" ").first())) })
        assertEquals(plan, ReelPlanCodec.decode(ReelPlanCodec.encode(plan)))
        assertTrue(runCatching { ReelPlanCodec.decode(ReelPlanCodec.encode(plan).replace("\"version\":1", "\"version\":99")) }.isFailure)
    }
    @Test fun compositionHasOneSequenceCorrectTrimsNoAudioAndGlobalOverlay() {
        val plan = plan()
        val composition = ReelCompositionFactory.create(plan)
        assertEquals(1, composition.sequences.size)
        val items = composition.sequences.single().editedMediaItems
        assertEquals(plan.clips.size, items.size)
        items.forEachIndexed { index, item ->
            assertTrue(item.removeAudio)
            assertEquals(plan.clips[index].trimStartMs, item.mediaItem.clippingConfiguration.startPositionMs)
            assertEquals(plan.clips[index].trimEndMs, item.mediaItem.clippingConfiguration.endPositionMs)
            assertEquals(1, item.effects.videoEffects.size)
        }
        assertEquals(1, composition.effects.videoEffects.size)
    }
    @Test fun overlayChangesOnBeatBoundaryAndReusesOneBitmap() {
        val plan = plan().copy(textBeats = listOf(TextBeat("FIRST", 0, 2_000), TextBeat("SECOND", 3_000, 5_000)))
        val overlay = TimedTextOverlay(plan)
        val first = overlay.getBitmap(1_000_000)
        val generation = first.generationId
        assertSame(first, overlay.getBitmap(1_500_000)); assertEquals(generation, first.generationId)
        val gap = overlay.getBitmap(2_000_000)
        assertSame(first, gap); assertEquals(Color.TRANSPARENT, gap.getPixel(ExportPolicy.WIDTH / 2, ExportPolicy.HEIGHT / 2))
        val second = overlay.getBitmap(3_000_000)
        assertNotEquals(generation, second.generationId)
        assertTrue((0 until second.width step 32).any { x ->
            (0 until second.height step 32).any { y -> second.getPixel(x, y) != Color.TRANSPARENT }
        })
        overlay.release(); assertTrue(second.isRecycled)
    }
}

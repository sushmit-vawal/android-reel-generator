package com.reelgenerator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BatchEngineTest {
    @Test fun rendersExactlyFiveSequentiallyWithFreshFootageFirst() = runTest {
        var active = 0
        var maximum = 0
        val result = runBatch((1..8).map { "clip$it" }) { slot, source ->
            active++; maximum = maxOf(maximum, active)
            delay(10)
            active--
            "$slot:$source"
        }
        assertEquals(5, result.outputs.size)
        assertEquals(5, result.outputs.map { it.source }.distinct().size)
        assertEquals(1, maximum)
    }
    @Test fun reusesSmallLibraryInsteadOfStoppingAtOne() = runTest {
        val result = runBatch(listOf("one")) { slot, _ -> "output$slot" }
        assertEquals(5, result.outputs.size)
    }
    @Test fun skipsCorruptSourceAndContinuesToFive() = runTest {
        var badAttempts = 0
        val result = runBatch(listOf("bad", "good")) { slot, source ->
            if (source == "bad") { badAttempts++; error("corrupt") }
            "output$slot"
        }
        assertEquals(1, badAttempts)
        assertEquals(5, result.outputs.size)
        assertEquals(listOf("corrupt"), result.errors)
    }
    @Test fun retainsCompletedOutputsWhenSourcesBecomeUnreadable() = runTest {
        val previous = listOf(BatchOutput("old", "saved"))
        val result = runBatch(listOf("bad"), previous) { _, _ -> error("access revoked") }
        assertEquals(previous, result.outputs)
    }
    @Test fun recoveryDoesNotRenderSavedSlotsAgain() = runTest {
        val slots = mutableListOf<Int>()
        val previous = (0..2).map { BatchOutput("clip$it", "saved$it") }
        val result = runBatch(listOf("next"), previous) { slot, _ -> slots.add(slot); "new$slot" }
        assertEquals(listOf(3, 4), slots)
        assertEquals(5, result.outputs.size)
    }
    @Test fun cancellationIsNotTreatedAsCorruptVideo() = runTest {
        var attempts = 0
        try {
            runBatch(listOf("a", "b")) { _, _ -> attempts++; throw CancellationException("stop") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, attempts) }
    }
    @Test fun boundedFailuresAndEmptyLibrary() = runTest {
        var attempts = 0
        val result = runBatch((1..100).map(Int::toString)) { _, _ -> attempts++; error("bad") }
        assertEquals(20, attempts)
        assertTrue(result.outputs.isEmpty())
        assertTrue(runBatch(emptyList()) { _, _ -> error("must not run") }.outputs.isEmpty())
    }
    @Test fun everyCategoryAndHumorModeHasFiveDistinctShortCaptions() {
        ReelCategory.entries.forEach { category -> HumorStyle.entries.forEach { humor ->
            for (seed in -10..10) {
                val captions = (0..4).map { PhaseTwoText.caption(category, humor, it, seed) }
                assertEquals(5, captions.distinct().size)
                assertTrue(captions.all { it.length in 10..100 })
            }
        } }
        assertNotEquals(PhaseTwoText.caption(ReelCategory.HUMOR, HumorStyle.FUNNY, 0, 0), PhaseTwoText.caption(ReelCategory.HUMOR, HumorStyle.DARK, 0, 0))
    }
}

package com.reelgenerator

import org.junit.Assert.*
import org.junit.Test

class ExportPolicyTest {
    @Test fun capsLongSourcesAndPreservesShortSources() {
        assertEquals(12_000L, ExportPolicy.duration(90_000))
        assertEquals(4_300L, ExportPolicy.duration(4_300))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnreadableDuration() { ExportPolicy.duration(0) }
    @Test fun exportsPortraitToExpectedAlbum() {
        assertEquals(9.0 / 16, ExportPolicy.WIDTH.toDouble() / ExportPolicy.HEIGHT, 0.00001)
        assertEquals("Movies/Upload Reels/", ExportPolicy.RELATIVE_PATH)
        assertEquals("travel_test.mp4", ExportPolicy.fileName(ReelCategory.TRAVEL, "test"))
    }
    @Test fun eachCategoryHasDistinctReadableText() {
        assertEquals(4, ReelCategory.entries.map { it.caption }.distinct().size)
        ReelCategory.entries.forEach { assertTrue(it.caption.length in 10..100) }
    }
}

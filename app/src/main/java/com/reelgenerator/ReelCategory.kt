package com.reelgenerator

enum class ReelCategory(val label: String, val caption: String) {
    LIFESTYLE("Lifestyle", "Make a little room\nfor the life you enjoy."),
    MOTIVATION("Motivation", "Small steps count.\nTake another today."),
    TRAVEL("Travel", "Give your routine\na different horizon."),
    HUMOR("Humor", "My plans are ambitious.\nMy energy has other plans.")
}

object ExportPolicy {
    /** 2K vertical output: 1440x2560 (9:16). */
    const val WIDTH = 1440
    const val HEIGHT = 2560
    const val MAX_DURATION_MS = 12_000L
    const val RELATIVE_PATH = "Movies/Upload Reels/"
    fun duration(sourceMs: Long): Long {
        require(sourceMs >= 1_000) { "Choose a video at least one second long." }
        return minOf(sourceMs, MAX_DURATION_MS)
    }
    fun fileName(category: ReelCategory, id: String) = "${category.name.lowercase(java.util.Locale.ROOT)}_$id.mp4"
}

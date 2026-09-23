package com.reelgenerator

enum class HumorStyle(val label: String) { AUTO("Auto"), FUNNY("Funny"), DARK("Dark") }

/** Five coherent, distinct test captions per category. No visual matching or AI claims. */
object PhaseTwoText {
    private val lifestyle = listOf("Leave a little space\nfor an unplanned afternoon.", "A quieter day\ncan still be a full one.", "Your pace belongs to you.\nMake it a kind one.", "Good company makes\nan ordinary day worth keeping.", "Build a life with moments\nyou want to stay in.")
    private val motivation = listOf("Start with what you have.\nShow up again tomorrow.", "Progress can be quiet.\nKeep doing the work.", "One focused hour\nis a promise kept to yourself.", "A difficult day\ndoesn't erase your effort.", "Let consistency\ncarry the ambition.")
    private val travel = listOf("Put a new place\non this year's calendar.", "Give your everyday view\na little competition.", "Work fills the week.\nSave room for a story.", "Choose the road\nyou haven't learned by heart.", "Bring home a memory\nthat won't fit in a suitcase.")
    private val funny = listOf("My schedule is packed.\nMostly with rescheduling.", "I made a five-year plan.\nThen I needed a snack.", "Running late counts\nas cardio, right?", "My budget and my wishlist\nare no longer speaking.", "Today felt productive.\nI closed seventeen tabs.")
    private val dark = listOf("My optimism is on leave.\nNo return date was given.", "Adulthood: the free trial\nended without warning.", "My inbox has ambitions\nI never agreed to.", "The light at the end\nis another meeting invite.", "I lowered my expectations.\nThey found a basement.")
    fun caption(category: ReelCategory, humor: HumorStyle, slot: Int, seed: Int): String {
        require(slot in 0..4)
        val choices = when (category) {
            ReelCategory.LIFESTYLE -> lifestyle
            ReelCategory.MOTIVATION -> motivation
            ReelCategory.TRAVEL -> travel
            ReelCategory.HUMOR -> if (humor == HumorStyle.DARK || (humor == HumorStyle.AUTO && slot % 2 == 1)) dark else funny
        }
        return choices[Math.floorMod(slot + Math.floorMod(seed, 5), 5)]
    }
}

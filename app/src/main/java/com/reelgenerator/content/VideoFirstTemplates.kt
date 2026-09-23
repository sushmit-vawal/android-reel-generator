package com.reelgenerator.content

import com.reelgenerator.ReelCategory
import com.reelgenerator.analysis.*

/** Footage-led templates, not AI-generated claims about events or people. */
object VideoFirstTemplates {
    fun candidates(category: ReelCategory, analyses: List<ClipAnalysis>): List<TextCandidate> {
        val subjects = analyses.flatMap { it.temporalSegments }.filter { it.quality >= .4 }.flatMap { VisualVocabulary.canonical(it.semanticTags).filterValues { score -> score >= .65 }.keys }.toSet()
        val allowed = when (category) {
            ReelCategory.MOTIVATION -> setOf("fitness", "work")
            ReelCategory.TRAVEL -> setOf("ocean", "airport", "mountain", "road", "sunrise", "city", "nature")
            ReelCategory.LIFESTYLE -> setOf("coffee", "food", "home", "social", "nature", "city", "sunrise")
            ReelCategory.HUMOR -> emptySet() // Classification cannot establish that an event is funny.
        }
        val lines = mapOf(
            "ocean" to listOf("The ocean sets the pace today.", "One more minute by the ocean.", "Let the ocean be the whole plan.", "An afternoon measured in waves.", "Nothing on the agenda but the sea.", "This is my kind of ocean view."),
            "airport" to listOf("The airport is where the next chapter starts.", "A little airport waiting. A lot to look forward to.", "That airport feeling before somewhere new.", "Every flight starts with a little anticipation.", "Another airplane window to look through.", "Taking the long way through the airport."),
            "mountain" to listOf("A mountain view worth pausing for.", "One mountain. A different perspective.", "Leave a little room for the mountains.", "The mountain makes everything feel smaller.", "A quiet moment in the mountains.", "There is more to see beyond this mountain."),
            "road" to listOf("The road is part of the experience.", "A new stretch of road ahead.", "Watching the road become a memory.", "Some roads deserve a second look.", "Another turn in the road.", "The road has its own rhythm."),
            "sunrise" to listOf("A sunrise worth making time for.", "Let the morning begin slowly.", "One more reason to notice the sunrise.", "Morning light changes the ordinary.", "A little quiet at sunrise.", "Keep this sunrise for a busy day."),
            "city" to listOf("Every city has a different rhythm.", "A city moment worth keeping.", "Another corner of the city.", "Watching the city move around me.", "Taking in the city, one street at a time.", "Another city detail to notice."),
            "nature" to listOf("A moment in nature changes the pace.", "Let nature fill the quiet.", "Make a little space for nature.", "Nature does not need a busy schedule.", "Another reason to spend time in nature.", "A quiet view of nature to keep."),
            "fitness" to listOf("Training starts with showing up.", "One workout is still progress.", "Make room for the next workout.", "Fitness is built one practice at a time.", "A little effort belongs in every workout.", "Let the workout speak for itself."),
            "work" to listOf("Progress can look like ordinary work.", "Keep showing up for the work.", "One focused hour of work at a time.", "The work is part of the story.", "Small steps still count at work.", "A little more care in today's work."),
            "coffee" to listOf("Coffee and a moment to pause.", "Let the coffee be the first plan.", "Another small coffee ritual.", "A coffee break worth taking.", "Keeping this coffee moment simple.", "There is time for one more coffee."),
            "food" to listOf("Good food makes room for a pause.", "A little food moment worth keeping.", "Let the food be the occasion.", "Another reason to enjoy the meal.", "Taking time over food today.", "Some meals deserve your full attention."),
            "home" to listOf("An ordinary moment at home.", "Home has its own rhythm.", "Making room for a quiet day at home.", "A little more time at home.", "The small details make a home.", "Keeping the home routine simple."),
            "social" to listOf("A little time with friends.", "Make room for the friends who matter.", "An ordinary day with friends is enough.", "Another moment to keep with friends.", "Friends make the everyday feel different.", "Time to catch up with friends.")
        )
        return subjects.intersect(allowed).flatMap { subject -> lines[subject].orEmpty().map { TextCandidate(listOf(it), subject, "VIDEO_FIRST_TEMPLATE", tags = subject) } }
    }
}

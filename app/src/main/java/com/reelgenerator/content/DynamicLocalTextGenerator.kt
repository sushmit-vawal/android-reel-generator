package com.reelgenerator.content

import com.reelgenerator.ReelCategory
import com.reelgenerator.analysis.ClipAnalysis
import com.reelgenerator.analysis.VisualVocabulary
import kotlin.random.Random

/** Compositional local writing. Outputs are templates assembled from footage/category evidence;
 * this is deliberately not labelled AI-generated text. */
object DynamicLocalTextGenerator {
    private val openings = mapOf(
        ReelCategory.TRAVEL to listOf("I almost stayed home", "The plan looked ordinary", "I needed a different view", "The day started slowly", "One small trip changed the pace"),
        ReelCategory.MOTIVATION to listOf("I did not feel ready", "Progress looked quiet today", "The hard part was starting", "Nobody sees every repetition", "Motivation was late again"),
        ReelCategory.LIFESTYLE to listOf("Nothing dramatic happened", "I kept this part simple", "The ordinary moment won", "Today needed less noise", "A small pause was enough"),
        ReelCategory.HUMOR to listOf("The plan was flawless", "I knew exactly what I was doing", "This looked easier in my head", "Confidence arrived first", "What could possibly go wrong")
    )
    private val transitions = listOf("Then the day made its point", "So I kept going", "That was when the mood changed", "A little patience helped", "The next part explained everything")
    private val payoffs = mapOf(
        "ocean" to listOf("The ocean was worth the wait", "The waves handled the rest", "This was the view I needed"),
        "airport" to listOf("The departure made it real", "The gate was only the beginning", "Some waiting leads somewhere good"),
        "mountain" to listOf("The mountain changed the perspective", "The climb gave the day a point", "The view answered for me"),
        "road" to listOf("The road became the best part", "The long way worked out", "The next turn was enough"),
        "fitness" to listOf("Showing up still counted", "The repetition became progress", "The work spoke for itself"),
        "work" to listOf("The quiet work moved things forward", "That small step still counted", "The routine was building something"),
        "coffee" to listOf("The coffee fixed the meeting agenda", "The first sip did the planning", "That pause earned its place"),
        "food" to listOf("The meal deserved the attention", "That bite was the whole plan", "The table slowed everything down"),
        "home" to listOf("Home was enough today", "The quiet part was the best part", "The ordinary room felt right"),
        "social" to listOf("The company made the moment", "The laugh was worth keeping", "The people were the best part"),
        "nature" to listOf("Nature lowered the volume", "The quiet view did its job", "The outside world reset the day"),
        "sunrise" to listOf("The morning light made the case", "The early start finally made sense", "The sunrise was the payoff"),
        "city" to listOf("The city supplied the energy", "The street found its own rhythm", "The city made ordinary look alive")
    )
    fun candidates(category: ReelCategory, analyses: Collection<ClipAnalysis>, seed: Int, count: Int = 48): List<TextCandidate> {
        val subjects = analyses.flatMap { it.temporalSegments }
            .flatMap { VisualVocabulary.canonical(it.semanticTags).filterValues { confidence -> confidence >= .50 }.keys }
            .distinct().sorted()
        if (subjects.isEmpty()) return emptyList()
        val random = Random(seed)
        return buildList {
            repeat(count) { index ->
                val subject = subjects[(index + random.nextInt(subjects.size)) % subjects.size]
                val first = openings.getValue(category)[(index + random.nextInt(openings.getValue(category).size)) % openings.getValue(category).size]
                val lastPool = payoffs[subject] ?: listOf("The moment was worth keeping", "That was enough for today", "The next part made sense")
                val last = lastPool[(index + random.nextInt(lastPool.size)) % lastPool.size]
                val beats = when (index % 3) {
                    0 -> listOf(last)
                    1 -> listOf(first, last)
                    else -> listOf(first, transitions[(index + random.nextInt(transitions.size)) % transitions.size], last)
                }
                add(TextCandidate(beats, subject, "DYNAMIC_LOCAL", tags = subject, preferredClips = beats.size))
            }
        }.distinctBy { TextIdentity.normalize(it.beats.joinToString(" ")) }
    }
}

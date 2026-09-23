package com.reelgenerator.analysis

import com.reelgenerator.ReelCategory
import com.reelgenerator.content.TextIdentity
import kotlin.math.abs

/** Scores are evidence strengths, not probabilities of human emotional interpretation. */
data class SemanticTag(val name: String, val confidence: Double, val evidence: String = "image-label")
data class SemanticEmbedding(val space: String, val values: List<Float>)
interface ClipEmbeddingProvider {
    val space: String
    suspend fun imageEmbedding(argb: IntArray, width: Int, height: Int): SemanticEmbedding?
    suspend fun textEmbedding(text: String): SemanticEmbedding?
}
object NoClipEmbeddingProvider : ClipEmbeddingProvider {
    override val space = "unavailable"
    override suspend fun imageEmbedding(argb: IntArray, width: Int, height: Int): SemanticEmbedding? = null
    override suspend fun textEmbedding(text: String): SemanticEmbedding? = null
}

data class VibeProfile(
    val energy: Double, val motion: Double, val brightness: Double, val complexity: Double,
    val mood: String, val setting: String, val shot: String, val confidence: Double
)
data class CropAssessment(val retainedWidth: Double, val centerInterest: Double, val confidence: Double) {
    val suitability get() = (0.6 + 0.4 * centerInterest).coerceIn(0.0, 1.0)
}
/** Renderer remains centered. This seam can later accept tracked subject regions. */
fun interface SubjectCropPolicy { fun assess(width: Int, height: Int, centerInterest: Double): CropAssessment }
object CenterInterestCropPolicy : SubjectCropPolicy {
    override fun assess(width: Int, height: Int, centerInterest: Double) = CropAssessment(
        minOf(1.0, height * 9.0 / (width * 16.0)), centerInterest, .3
    )
}
data class ClipTemporalSegment(
    val startMs: Long, val endMs: Long, val semanticTags: List<SemanticTag>, val vibe: VibeProfile,
    val quality: Double, val crop: CropAssessment, val semanticEmbedding: SemanticEmbedding? = null
)
data class ClipAnalysis(
    val sourceUri: String, val fingerprint: String, val provider: String,
    val temporalSegments: List<ClipTemporalSegment>, val confidence: Double
)
data class BeatVisualIntent(
    val text: String, val subjects: Set<String>, val conceptSubjects: Set<String>,
    val desiredEnergy: Double, val desiredMood: String, val role: String, val confidence: Double
)
data class MatchPolicy(
    val minimumSemanticMatch: Double = .50, val minimumVibeMatch: Double = .48,
    val minimumOverallVisualMatch: Double = .58, val minimumQuality: Double = .25,
    val maximumReusePenalty: Double = .07
)
data class VisualMatchScore(
    val semantic: Double, val concept: Double, val vibe: Double, val motion: Double,
    val quality: Double, val reusePenalty: Double, val overall: Double, val eligible: Boolean
)

/** Explicit, inspectable language rules. This is not an LLM or an emotion classifier. */
object VisualVocabulary {
    private val groups = linkedMapOf(
        "work" to "work working office laptop computer keyboard desk job routine boring mondays",
        "airport" to "airport airplane aircraft aviation flight flying aeroplane runway boarding packing luggage takeoff",
        "road" to "road driving car vehicle motorcycle highway traffic taxi train railway transport",
        "ocean" to "ocean sea beach coast shore water underwater scuba diving snorkeling swimming surf wave",
        "mountain" to "mountain hiking hike climbing hill summit adventure",
        "nature" to "nature forest tree garden park landscape wilderness flower vegetation outdoors",
        "sunrise" to "sunrise sunset dawn dusk sunlight horizon morning mornings",
        "fitness" to "gym workout training fitness exercise running runner weights weightlifting bodybuilding sport effort practice progress discipline motivation motivated showed",
        "city" to "city urban building architecture street skyscraper nightlife",
        "home" to "home house room kitchen bedroom livingroom domestic",
        "coffee" to "coffee cafe cup espresso cappuccino caffeine",
        "food" to "food meal cooking restaurant cuisine eating dessert lunch dinner",
        "social" to "friends friendship group party celebration wedding dance dancing smile laughter laughing"
    )
    fun subjects(text: String): Set<String> {
        val words = TextIdentity.normalize(text).split(' ').toSet()
        return groups.filterValues { names -> names.split(' ').any { it in words } }.keys
    }
    fun canonical(tags: List<SemanticTag>): Map<String, Double> = buildMap {
        tags.forEach { tag -> subjects(tag.name).forEach { key -> put(key, maxOf(get(key) ?: 0.0, tag.confidence)) } }
    }
    fun intents(beats: List<String>, concept: String, tags: String, category: ReelCategory): List<BeatVisualIntent> {
        val context = subjects("$concept $tags")
        val whole = subjects(beats.joinToString(" "))
        val workTravel = category == ReelCategory.TRAVEL && "work" in whole && beats.size >= 3
        return beats.mapIndexed { index, text ->
            val normalized = TextIdentity.normalize(text)
            val calm = listOf("slow", "peace", "quiet", "breathe", "rest", "relax", "reflect", "afternoon").any { it in normalized }
            val active = listOf("adventure", "push", "effort", "showed up", "train", "workout", "run", "intense").any { it in normalized }
            val explicit = subjects(text)
            val travelIdea = category == ReelCategory.TRAVEL && listOf("travel", "vacation", "trip", "escape", "freedom", "explor", "discover", "journey", "memories", "moments", "world").any { it in normalized }
            val everydayIdea = category == ReelCategory.LIFESTYLE && listOf("routine", "ordinary", "little things", "everyday", "weekend", "simple").any { it in normalized }
            val role = when { index == 0 -> "setup"; index == beats.lastIndex -> "payoff"; else -> "transition" }
            val inferred = when {
                explicit.isNotEmpty() -> explicit
                workTravel && role == "transition" -> setOf("airport", "road")
                workTravel && role == "payoff" -> setOf("ocean", "mountain")
                context.isNotEmpty() -> context
                calm -> setOf("nature", "sunrise", "ocean", "home")
                travelIdea -> setOf("ocean", "mountain", "nature", "city", "airport")
                everydayIdea -> setOf("home", "coffee", "food", "social")
                whole.isNotEmpty() -> whole
                category == ReelCategory.MOTIVATION -> setOf("fitness", "work")
                // Broad category alone is insufficient evidence for travel/lifestyle/humor.
                else -> emptySet()
            }
            val energy = when { calm -> .12; active || "fitness" in inferred -> .65; workTravel && role == "setup" -> .2; else -> .35 }
            BeatVisualIntent(text, inferred, context, energy, if (calm) "peaceful" else if (active) "active" else "neutral", role,
                if (explicit.isNotEmpty()) .9 else .5)
        }
    }
}

object VisualScorer {
    fun score(intent: BeatVisualIntent, segment: ClipTemporalSegment, uses: Int, paired: Boolean, policy: MatchPolicy): VisualMatchScore {
        val tags = VisualVocabulary.canonical(segment.semanticTags)
        val semantic = intent.subjects.maxOfOrNull { tags[it] ?: 0.0 } ?: 0.0
        val concept = if (intent.conceptSubjects.isEmpty()) semantic else intent.conceptSubjects.maxOf { tags[it] ?: 0.0 }
        val motion = (1.0 - abs(intent.desiredEnergy - segment.vibe.motion)).coerceIn(0.0, 1.0)
        val vibe = (1.0 - abs(intent.desiredEnergy - segment.vibe.energy)).coerceIn(0.0, 1.0)
        val reuse = minOf(policy.maximumReusePenalty, uses.coerceAtLeast(0) * .012 + if (paired) .03 else 0.0)
        val overall = .57 * semantic + .05 * concept + .17 * vibe + .08 * motion + .08 * segment.quality + .05 * segment.crop.suitability - reuse
        return VisualMatchScore(semantic, concept, vibe, motion, segment.quality, reuse, overall,
            semantic >= policy.minimumSemanticMatch && vibe >= policy.minimumVibeMatch && overall >= policy.minimumOverallVisualMatch && segment.quality >= policy.minimumQuality)
    }
    fun continuity(a: ClipTemporalSegment, b: ClipTemporalSegment, deliberateEnergyChange: Double): Double =
        (.06 * abs(a.vibe.brightness - b.vibe.brightness) + .04 * (abs(a.vibe.motion - b.vibe.motion) - deliberateEnergyChange).coerceAtLeast(0.0)
            + .02 * abs(a.crop.retainedWidth - b.crop.retainedWidth)).coerceAtMost(.12)
}

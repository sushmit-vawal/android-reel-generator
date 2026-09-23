package com.reelgenerator

import com.reelgenerator.analysis.*
import com.reelgenerator.content.*
import com.reelgenerator.data.*
import com.reelgenerator.planning.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VisualMatchingTest {
    private fun clip(n: Int) = SourceClip("$n", "content://video/$n", 40000, 1920, 1080)
    private fun section(tag: String, start: Long = 0, end: Long = 40000, energy: Double = .2, confidence: Double = .94, brightness: Double = .5) =
        ClipTemporalSegment(start, end, listOf(SemanticTag(tag, confidence)), VibeProfile(energy, energy, brightness, .4, "heuristic", tag, "unknown", .4), .9, CropAssessment(.32, .8, .3))
    private fun analysis(clip: SourceClip, vararg sections: ClipTemporalSegment) = ClipAnalysis(clip.uri, "fingerprint", "test", sections.toList(), .9)
    private fun intent(text: String, category: ReelCategory = ReelCategory.TRAVEL) = VisualVocabulary.intents(listOf(text), "", "", category).single()
    private fun request(clips: List<SourceClip>, category: ReelCategory = ReelCategory.TRAVEL) = PlanningRequest("test", category, HumorStyle.AUTO, 0, 12, clips)
    private fun item(i: Int, text: String, category: String = "TRAVEL") = ContentLibraryItem("item$i", "import", "USER_CSV", category, "", text, JSONArray(listOf(text)).toString(), "", null, null, null, "hash$i")

    @Test fun reusedRelevantBeatsFreshWeakAndUnrelated() {
        val wanted = intent("The ocean is calling")
        val a = VisualScorer.score(wanted, section("ocean"), 1000, true, MatchPolicy())
        val b = VisualScorer.score(wanted, section("ocean", confidence = .52), 0, false, MatchPolicy())
        val c = VisualScorer.score(wanted, section("office"), 0, false, MatchPolicy())
        assertTrue(a.overall > b.overall); assertFalse(c.eligible); assertEquals(.07, a.reusePenalty, .0001)
    }
    @Test fun calmTextRejectsFastMotionEvenWithCorrectSubject() {
        val wanted = intent("Slow down by the ocean")
        assertTrue(VisualScorer.score(wanted, section("ocean", energy = .1), 0, false, MatchPolicy()).eligible)
        assertFalse(VisualScorer.score(wanted, section("ocean", energy = .95), 0, false, MatchPolicy()).eligible)
    }
    @Test fun motivationDemandsEffortInsteadOfGenericScenery() {
        val wanted = intent("No motivation. Still showed up.", ReelCategory.MOTIVATION)
        assertTrue(VisualScorer.score(wanted, section("weightlifting", energy = .65), 0, false, MatchPolicy()).eligible)
        assertFalse(VisualScorer.score(wanted, section("mountain"), 0, false, MatchPolicy()).eligible)
    }
    @Test fun thresholdsAreConfigurableAndUnknownHumorDoesNotAcceptAnything() {
        val score = VisualScorer.score(intent("Ocean"), section("ocean", confidence = .7), 0, false, MatchPolicy(minimumSemanticMatch = .8))
        assertFalse(score.eligible)
        assertTrue(intent("Well that went brilliantly", ReelCategory.HUMOR).subjects.isEmpty())
    }
    @Test fun beachWorkoutRequiresActivityNotJustTheBeachSetting() {
        val wanted = intent("A beach workout worth showing up for", ReelCategory.MOTIVATION)
        assertFalse(VisualScorer.score(wanted, section("beach", energy = .6), 0, false, MatchPolicy()).eligible)
        assertTrue(VisualScorer.score(wanted, section("exercise", energy = .6), 0, false, MatchPolicy()).eligible)
    }
    @Test fun workTransitionPayoffIntentAndTemporalSequence() {
        val texts = listOf("Some days I work hard.", "Then I remember...", "This is what I am buying back.")
        val intents = VisualVocabulary.intents(texts, "", "", ReelCategory.TRAVEL)
        assertEquals(setOf("work"), intents[0].subjects); assertTrue("airport" in intents[1].subjects); assertTrue("ocean" in intents[2].subjects)
        val source = clip(1)
        val evidence = analysis(source, section("office", 0, 10000), section("airport", 12000, 22000), section("ocean", 26000, 40000))
        val result = VisualPlanMatcher().match(request(listOf(source)), TextCandidate(texts, "", "USER_CSV", "csv"), mapOf(source.uri to evidence), emptyList(), emptyMap(), emptyList(), mutableListOf())!!
        assertEquals(texts, result.plan.textBeats.map { it.text })
        assertEquals(listOf(0L, 12000L, 26000L), result.plan.clips.map { it.trimStartMs })
        assertTrue(result.plan.metadata.notes.any { it.startsWith("visualMatch=") })
    }
    @Test fun sequenceContinuityBreaksCloseSemanticTie() {
        val a = clip(1); val b = clip(2); val c = clip(3)
        val evidence = mapOf(a.uri to analysis(a, section("work", brightness = .7)), b.uri to analysis(b, section("ocean", brightness = .72)), c.uri to analysis(c, section("ocean", brightness = .1)))
        val plan = VisualPlanMatcher().match(request(listOf(a, c, b)), TextCandidate(listOf("Work today", "Ocean tomorrow"), "", "USER_CSV"), evidence, emptyList(), emptyMap(), emptyList(), mutableListOf())!!.plan
        assertEquals(listOf(a.uri, b.uri), plan.clips.map { it.source.uri })
    }
    @Test fun readableSingleBeatCanUseSeveralShortMatchingRanges() {
        val a = clip(1); val b = clip(2)
        val evidence = mapOf(a.uri to analysis(a, section("ocean", 1000, 6000)), b.uri to analysis(b, section("ocean", 21000, 26000)))
        val plan = VisualPlanMatcher().match(request(listOf(a, b)), TextCandidate(listOf("The ocean waits"), "", "USER_CSV"), evidence, emptyList(), emptyMap(), emptyList(), mutableListOf())!!.plan
        assertEquals(2, plan.clips.size); assertEquals(1, plan.textBeats.size)
        assertTrue(plan.clips.any { it.source.uri == b.uri && it.trimStartMs >= 21000 })
    }
    @Test fun laterLibraryRowsAndVideosBeyondFortyRemainEligible() = runTest {
        val clips = (0 until 550).map(::clip)
        val evidence = clips.associate { it.uri to analysis(it, section(if (it.id == "549") "ocean" else "work")) }
        val rows = (0 until 120).map { item(it, if (it == 119) "Ocean moment number $it" else "Airport moment number $it") }
        val result = ContentCandidatePlanner().select(request(clips), rows, emptyList(), emptyList(), emptyMap(), evidence)
        assertEquals(clips.last().uri, result.clips.single().source.uri)
        assertTrue(result.metadata.notes.contains("contentItemId=item119"))
    }
    @Test fun hundredRowsAreUsableOverRepeatedSelectionsWithoutFixedTwelveCutoff() = runTest {
        val source = clip(1); val evidence = mapOf(source.uri to analysis(source, section("ocean")))
        val items = (0 until 105).map { item(it, "Ocean moment $it") }
        val history = mutableListOf<String>()
        repeat(105) { i ->
            val result = ContentCandidatePlanner().select(request(listOf(source)).copy(seed = i), items, emptyList(), history, emptyMap(), evidence)
            assertTrue(result.metadata.notes.contains("textSource=USER_CSV"))
            history.add(result.textBeats.single().text)
        }
        assertEquals(105, history.toSet().size)
    }
    @Test fun strongImportedWordingIsUnchangedAndBadMatchIsDeferred() = runTest {
        val source = clip(1); val evidence = mapOf(source.uri to analysis(source, section("ocean")))
        val good = "My exact ocean wording!"
        val result = ContentCandidatePlanner().select(request(listOf(source)), listOf(item(1, "Office laptop"), item(2, good)), emptyList(), emptyList(), emptyMap(), evidence)
        assertEquals(good, result.textBeats.single().text)
    }
    @Test fun videoFirstUsesObservedSubjectsAndNeverFabricatesFunnyEvents() {
        val available = listOf(analysis(clip(1), section("ocean")))
        val candidates = VideoFirstTemplates.candidates(ReelCategory.TRAVEL, available)
        assertEquals(6, candidates.size); assertTrue(candidates.all { it.source == "VIDEO_FIRST_TEMPLATE" && it.tags == "ocean" })
        assertTrue(VideoFirstTemplates.candidates(ReelCategory.HUMOR, available).isEmpty())
        assertTrue(VideoFirstTemplates.candidates(ReelCategory.MOTIVATION, available).isEmpty())
    }
    @Test fun shuffledCoverageIncludesEveryVideoAndEveryFolderEarly() {
        val videos = (0 until 550).map { SourceVideo("content://$it", "v$it", 1, 1) }
        val links = videos.mapIndexed { i, v -> FolderVideo("folder${i % 5}", v.uri) }
        val first = LibraryCoverage.order(videos, links, 1)
        assertEquals(videos.map { it.uri }.toSet(), first.map { it.uri }.toSet())
        assertEquals(5, first.take(5).map { it.name.substringAfter('v').toInt() % 5 }.toSet().size)
        assertNotEquals(first.take(12), LibraryCoverage.order(videos, links, 2).take(12))
    }
    @Test fun fingerprintInvalidatesChangedFileAndCodecRetainsTemporalVibe() {
        val video = SourceVideo("content://1", "name", 1, 2)
        assertNotEquals(VisualClipAnalyzer.fingerprint(video), VisualClipAnalyzer.fingerprint(video.copy(modified = 2)))
        assertNotEquals(VisualClipAnalyzer.fingerprint(video), VisualClipAnalyzer.fingerprint(video.copy(size = 5)))
        val expected = analysis(clip(1), section("work", 0, 10000), section("ocean", 20000, 40000))
        assertEquals(expected, ClipAnalysisCodec.decode(ClipAnalysisCodec.encode(expected)))
    }
    @Test fun frameMetricsUseWholeImageAndNearbyMovementNotExposureChanges() {
        val a = IntArray(1024) { if (it % 32 < 16) 0xff404040.toInt() else 0xff909090.toInt() }
        val shifted = IntArray(1024) { if (it % 32 < 16) 0xff606060.toInt() else 0xffb0b0b0.toInt() }
        val moving = a.reversedArray()
        assertEquals(0.0, FramePixels.measure(a, shifted, 32, 32).motion, .001)
        assertTrue(FramePixels.measure(a, moving, 32, 32).motion > .5)
        assertTrue(FramePixels.measure(IntArray(1024), IntArray(1024), 32, 32).quality < .1)
    }
    @Test fun temporalMergeKeepsDifferentScenesSeparate() {
        val merged = VisualClipAnalyzer.merge(listOf(section("ocean", 0, 4000), section("ocean", 4000, 8000), section("work", 8000, 12000)))
        assertEquals(2, merged.size); assertEquals(8000L, merged.first().endMs)
    }
}

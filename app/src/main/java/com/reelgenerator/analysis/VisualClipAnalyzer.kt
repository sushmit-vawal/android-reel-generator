package com.reelgenerator.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.reelgenerator.content.ContentCsvParser
import com.reelgenerator.data.SourceVideo
import com.reelgenerator.planning.SourceClip
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.sqrt

data class PixelEvidence(val brightness: Double, val complexity: Double, val motion: Double, val quality: Double, val centerInterest: Double)
object FramePixels {
    // Small luma grids compare nearby frames. Camera motion and object motion remain inseparable.
    fun measure(first: IntArray, second: IntArray, width: Int, height: Int): PixelEvidence {
        require(first.size == width * height && second.size == first.size && width > 1 && height > 1)
        fun luma(p: Int) = (((p shr 16) and 255) * .2126 + ((p shr 8) and 255) * .7152 + (p and 255) * .0722) / 255
        val a = first.map(::luma); val b = second.map(::luma)
        val brightness = a.average(); val complexity = sqrt(a.map { (it - brightness) * (it - brightness) }.average()) * 3
        val meanShift = b.average() - brightness
        val motion = a.indices.sumOf { abs((b[it] - a[it]) - meanShift) } / a.size * 5
        var edges = 0.0; var center = 0.0
        for (y in 1 until height) for (x in 1 until width) {
            val i = y * width + x
            val edge = abs(a[i] - a[i - 1]) + abs(a[i] - a[i - width])
            edges += edge
            if (x in width * 3 / 8..width * 5 / 8) center += edge
        }
        val exposure = if (brightness < .025 || brightness > .985) .05 else 1.0
        val detail = (edges / a.size * 25).coerceIn(.15, 1.0)
        return PixelEvidence(brightness, complexity.coerceIn(0.0, 1.0), motion.coerceIn(0.0, 1.0),
            exposure * (.4 + .6 * detail), if (edges > .001) (center / edges * 4).coerceIn(0.0, 1.0) else .5)
    }
}

/** One bundled on-device labeler per indexing pass; no network/model download. */
class VisualClipAnalyzer(private val context: Context) : AutoCloseable {
    private var labeler: ImageLabeler? = null
    private fun client(): ImageLabeler = labeler ?: ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(.55f).build()).also { labeler = it }
    suspend fun analyze(source: SourceClip, video: SourceVideo): ClipAnalysis = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var modelSucceeded = false
        try {
            retriever.setDataSource(context, Uri.parse(source.uri))
            val count = when { source.durationMs <= 30_000 -> 6; source.durationMs <= 60_000 -> 12; else -> 18 }
            val times = (0 until count).map { (source.durationMs * (.10 + .75 * it / (count - 1))).toLong() }
            val samples = times.mapIndexedNotNull { index, time ->
                currentCoroutineContext().ensureActive()
                val frame = retriever.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320) ?: return@mapIndexedNotNull null
                try {
                    val labels = try {
                        // Tasks have no reliable cancellation. Retain the bitmap until the native task completes.
                        val result = suspendCoroutine<List<SemanticTag>> { continuation ->
                            client().process(InputImage.fromBitmap(frame, 0))
                                .addOnSuccessListener { values -> continuation.resume(values.map { SemanticTag(it.text, it.confidence.toDouble()) }) }
                                .addOnFailureListener { continuation.resumeWithException(it) }
                        }
                        modelSucceeded = true
                        result
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                    currentCoroutineContext().ensureActive()
                    val nearby = retriever.getScaledFrameAtTime(minOf(source.durationMs - 1, time + 200) * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 320, 320)
                    val pixels = try { measure(frame, nearby ?: frame) } finally { nearby?.recycle() }
                    // Filename evidence is weaker and used only when classification has no useful subject.
                    val tags = if (VisualVocabulary.canonical(labels).isNotEmpty()) labels else labels + VisualVocabulary.subjects(video.name).map { SemanticTag(it, .52, "filename-hint") }
                    val canonical = VisualVocabulary.canonical(tags)
                    val setting = canonical.maxByOrNull { it.value }?.key ?: "unknown"
                    val energy = (pixels.motion * .8 + if ("fitness" in canonical || "road" in canonical) .2 else .05).coerceIn(0.0, 1.0)
                    val vibe = VibeProfile(energy, pixels.motion, pixels.brightness, pixels.complexity,
                        if (energy < .25) "low-motion" else if (energy > .65) "high-motion" else "moderate-motion", setting,
                        if (setting in setOf("ocean", "mountain", "nature")) "possibly-scenic" else "unknown", .35)
                    val left = if (index == 0) 0 else (times[index - 1] + time) / 2
                    val right = if (index == times.lastIndex) source.durationMs else (time + times[index + 1]) / 2
                    ClipTemporalSegment(maxOf(left, time - 3000), minOf(right, time + 3000), tags, vibe, pixels.quality,
                        CenterInterestCropPolicy.assess(source.width, source.height, pixels.centerInterest))
                } finally { frame.recycle() }
            }
            ClipAnalysis(source.uri, fingerprint(video), if (modelSucceeded) MODEL else "pixel-and-filename-fallback", merge(samples), if (modelSucceeded) .65 else .3)
        } finally { retriever.release() }
    }
    private fun measure(a: Bitmap, b: Bitmap): PixelEvidence {
        val first = Bitmap.createScaledBitmap(a, 32, 32, true)
        val second = Bitmap.createScaledBitmap(b, 32, 32, true)
        try {
            val x = IntArray(1024); val y = IntArray(1024)
            first.getPixels(x, 0, 32, 0, 0, 32, 32); second.getPixels(y, 0, 32, 0, 0, 32, 32)
            return FramePixels.measure(x, y, 32, 32)
        } finally { if (first !== a) first.recycle(); if (second !== b && second !== first) second.recycle() }
    }
    override fun close() { labeler?.close() }
    companion object {
        const val MODEL = "mlkit-bundled-labels-17.0.9-v1"
        fun fingerprint(video: SourceVideo) = ContentCsvParser.hash("$MODEL|${video.uri}|${video.modified}|${video.size}|${video.name}")
        fun merge(samples: List<ClipTemporalSegment>): List<ClipTemporalSegment> {
            val result = mutableListOf<ClipTemporalSegment>()
            samples.forEach { next ->
                val last = result.lastOrNull()
                val a = last?.let { VisualVocabulary.canonical(it.semanticTags).keys }.orEmpty()
                val b = VisualVocabulary.canonical(next.semanticTags).keys
                if (last != null && last.endMs == next.startMs && a.isNotEmpty() && a == b && abs(last.vibe.energy - next.vibe.energy) < .2 && abs(last.vibe.brightness - next.vibe.brightness) < .2) {
                    // Conservative merged evidence: retain the lower confidence/quality across samples.
                    val tags = a.map { SemanticTag(it, minOf(VisualVocabulary.canonical(last.semanticTags).getValue(it), VisualVocabulary.canonical(next.semanticTags).getValue(it)), "merged-samples") }
                    result[result.lastIndex] = last.copy(endMs = next.endMs, semanticTags = tags, quality = minOf(last.quality, next.quality))
                } else result.add(next)
            }
            return result
        }
    }
}

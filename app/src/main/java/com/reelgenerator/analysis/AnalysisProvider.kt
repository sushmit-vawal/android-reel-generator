package com.reelgenerator.analysis

import android.net.Uri
import android.content.Context
import android.media.MediaMetadataRetriever

/** Phase 3B seam for local or remote analysis providers. Implementations must be explicit about confidence. */
interface FrameAnalysisProvider {
    suspend fun analyze(request: FrameAnalysisRequest): FrameAnalysis
}

data class FrameAnalysisRequest(val uri: Uri, val sampleTimesMs: List<Long>)

data class FrameAnalysis(
    val uri: Uri,
    val samples: List<FrameSample>,
    val provider: String,
    val confidence: Float?,
    val cacheKey: String
)

data class FrameSample(
    val timeMs: Long,
    val luminance: Float?,
    val motion: Float?,
    val sceneLabel: String? = null
)

/** Safe default until a real provider is configured. It never invents semantic labels. */
class UnavailableFrameAnalysisProvider : FrameAnalysisProvider {
    override suspend fun analyze(request: FrameAnalysisRequest) = FrameAnalysis(
        uri = request.uri,
        samples = request.sampleTimesMs.map { FrameSample(it, null, null) },
        provider = "unavailable",
        confidence = null,
        cacheKey = request.uri.toString()
    )
}

class LocalFrameAnalysisProvider(private val context: Context) : FrameAnalysisProvider {
    override suspend fun analyze(request: FrameAnalysisRequest): FrameAnalysis {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, request.uri)
            var previous: Float? = null
            val samples = request.sampleTimesMs.map { time ->
                val bitmap = retriever.getFrameAtTime(time * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)
                val luminance = bitmap?.let {
                    val pixel = it.getPixel(it.width / 2, it.height / 2)
                    (0.2126f * android.graphics.Color.red(pixel) + 0.7152f * android.graphics.Color.green(pixel) + 0.0722f * android.graphics.Color.blue(pixel)) / 255f
                }
                val motion = if (luminance != null && previous != null) (luminance - previous!!).let { kotlin.math.abs(it) } else null
                previous = luminance
                bitmap?.recycle()
                FrameSample(time, luminance, motion)
            }
            FrameAnalysis(request.uri, samples, "local-pixels", 0.25f, request.uri.toString())
        } finally { retriever.release() }
    }
}

class CachedFrameAnalysisProvider(private val delegate: FrameAnalysisProvider, private val cache: FrameAnalysisCache) : FrameAnalysisProvider {
    override suspend fun analyze(request: FrameAnalysisRequest): FrameAnalysis {
        val key = request.uri.toString() + ":" + request.sampleTimesMs.joinToString(",")
        cache.get(key)?.let { return it }
        return delegate.analyze(request).copy(cacheKey = key).also(cache::put)
    }
}

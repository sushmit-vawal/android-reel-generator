package com.reelgenerator.analysis

import android.net.Uri

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

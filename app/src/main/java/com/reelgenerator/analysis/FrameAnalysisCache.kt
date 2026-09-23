package com.reelgenerator.analysis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small bounded disk cache for provider output; semantic labels are never inferred here. */
class FrameAnalysisCache(context: Context, private val maxEntries: Int = 128) {
    private val file = File(context.cacheDir, "frame-analysis-cache.json")

    @Synchronized fun get(key: String): FrameAnalysis? = runCatching {
        if (!file.exists()) return null
        val root = JSONObject(file.readText())
        val value = root.optJSONObject(key) ?: return null
        FrameAnalysis(UriParser.parse(value.getString("uri")), value.getJSONArray("samples").toSamples(), value.getString("provider"), value.optDouble("confidence").takeUnless { it.isNaN() }?.toFloat(), key)
    }.getOrNull()

    @Synchronized fun put(value: FrameAnalysis) {
        val root = runCatching { if (file.exists()) JSONObject(file.readText()) else JSONObject() }.getOrDefault(JSONObject())
        root.put(value.cacheKey, JSONObject().put("uri", value.uri.toString()).put("provider", value.provider).put("confidence", value.confidence ?: JSONObject.NULL).put("samples", value.samples.toJson()))
        while (root.length() > maxEntries) root.keys().asSequence().firstOrNull()?.let(root::remove) ?: break
        file.writeText(root.toString())
    }
}

private object UriParser { fun parse(value: String) = android.net.Uri.parse(value) }
private fun List<FrameSample>.toJson() = JSONArray().also { array -> forEach { array.put(JSONObject().put("timeMs", it.timeMs).put("luminance", it.luminance ?: JSONObject.NULL).put("motion", it.motion ?: JSONObject.NULL).put("sceneLabel", it.sceneLabel ?: JSONObject.NULL)) } }
private fun JSONArray.toSamples() = (0 until length()).map { i -> getJSONObject(i).let { FrameSample(it.getLong("timeMs"), it.optDouble("luminance").takeUnless { n -> n.isNaN() }?.toFloat(), it.optDouble("motion").takeUnless { n -> n.isNaN() }?.toFloat(), it.optString("sceneLabel").takeUnless(String::isEmpty)) } }

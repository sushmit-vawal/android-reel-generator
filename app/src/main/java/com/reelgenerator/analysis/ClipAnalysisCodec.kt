package com.reelgenerator.analysis

import org.json.JSONArray
import org.json.JSONObject

object ClipAnalysisCodec {
    fun encode(analysis: ClipAnalysis): String = JSONObject().apply {
        put("uri", analysis.sourceUri); put("fingerprint", analysis.fingerprint); put("provider", analysis.provider); put("confidence", analysis.confidence)
        put("segments", JSONArray(analysis.temporalSegments.map { s -> JSONObject().apply {
            put("start", s.startMs); put("end", s.endMs); put("quality", s.quality)
            put("tags", JSONArray(s.semanticTags.map { t -> JSONObject().put("name", t.name).put("confidence", t.confidence).put("evidence", t.evidence) }))
            put("vibe", JSONObject().apply {
                put("energy", s.vibe.energy); put("motion", s.vibe.motion); put("brightness", s.vibe.brightness); put("complexity", s.vibe.complexity)
                put("mood", s.vibe.mood); put("setting", s.vibe.setting); put("shot", s.vibe.shot); put("confidence", s.vibe.confidence)
            })
            put("crop", JSONObject().put("width", s.crop.retainedWidth).put("interest", s.crop.centerInterest).put("confidence", s.crop.confidence))
        } }))
    }.toString()
    fun decode(json: String): ClipAnalysis {
        val root = JSONObject(json)
        val array = root.getJSONArray("segments")
        return ClipAnalysis(root.getString("uri"), root.getString("fingerprint"), root.getString("provider"), (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val v = s.getJSONObject("vibe"); val c = s.getJSONObject("crop"); val tags = s.getJSONArray("tags")
            ClipTemporalSegment(s.getLong("start"), s.getLong("end"), (0 until tags.length()).map { n -> tags.getJSONObject(n).let {
                SemanticTag(it.getString("name"), it.getDouble("confidence"), it.getString("evidence"))
            } }, VibeProfile(v.getDouble("energy"), v.getDouble("motion"), v.getDouble("brightness"), v.getDouble("complexity"), v.getString("mood"), v.getString("setting"), v.getString("shot"), v.getDouble("confidence")),
                s.getDouble("quality"), CropAssessment(c.getDouble("width"), c.getDouble("interest"), c.getDouble("confidence")))
        }, root.getDouble("confidence"))
    }
}

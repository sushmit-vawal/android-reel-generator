package com.reelgenerator.planning

import com.reelgenerator.HumorStyle
import com.reelgenerator.ReelCategory
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, local plan snapshot. Uses Android's JSON implementation; no new runtime dependency. */
object ReelPlanCodec {
    fun encode(plan: ReelPlan): String {
        plan.validated()
        return JSONObject().apply {
            put("version", 2); put("id", plan.id); put("category", plan.category.name); put("humor", plan.humorStyle.name)
            put("concept", plan.concept); put("angle", plan.emotionalAngle); put("hook", plan.hook); put("payoff", plan.payoff)
            put("pacing", plan.pacing.name); put("quality", plan.qualityScore ?: JSONObject.NULL)
            put("style", JSONObject().apply {
                put("preset", plan.textStyle.preset.name); put("fontSize", plan.textStyle.fontSizePx)
                put("typeface", plan.textStyle.typeface); put("alignment", plan.textStyle.alignment.name)
                put("treatment", plan.textStyle.treatment.name); put("foreground", plan.textStyle.foregroundColor)
                put("emphasisColor", plan.textStyle.emphasisColor); put("lineCount", plan.textStyle.lineCount)
                put("placementX", plan.textStyle.placementX); put("placementY", plan.textStyle.placementY)
                put("safe", JSONObject().put("left", plan.textStyle.safeArea.left).put("top", plan.textStyle.safeArea.top)
                    .put("right", plan.textStyle.safeArea.right).put("bottom", plan.textStyle.safeArea.bottom)
                    .put("controls", plan.textStyle.safeArea.rightControls))
            })
            put("clips", JSONArray().apply { plan.clips.forEach { segment -> put(JSONObject().apply {
                put("source", JSONObject().apply {
                    put("id", segment.source.id); put("uri", segment.source.uri); put("duration", segment.source.durationMs)
                    put("width", segment.source.width); put("height", segment.source.height)
                })
                put("trimStart", segment.trimStartMs); put("trimEnd", segment.trimEndMs); put("outputStart", segment.outputStartMs)
            }) } })
            put("beats", JSONArray().apply { plan.textBeats.forEach { beat -> put(JSONObject().apply {
                put("text", beat.text); put("start", beat.startMs); put("end", beat.endMs); put("emphasis", JSONArray(beat.emphasis))
            }) } })
            put("metadata", JSONObject().apply {
                put("planner", plan.metadata.plannerVersion); put("seed", plan.metadata.seed); put("created", plan.metadata.createdAtMs)
                put("notes", JSONArray(plan.metadata.notes)); put("fallback", plan.metadata.fallbackReason ?: JSONObject.NULL)
            })
        }.toString()
    }
    fun decode(json: String): ReelPlan {
        require(json.length <= 100_000) { "Plan is too large." }
        val root = JSONObject(json)
        val version = root.getInt("version")
        require(version == 1 || version == 2) { "Unsupported plan version." }
        val style = root.optJSONObject("style") ?: JSONObject()
        val meta = root.getJSONObject("metadata")
        return ReelPlan(root.getString("id"), ReelCategory.valueOf(root.getString("category")), HumorStyle.valueOf(root.getString("humor")),
            root.getString("concept"), root.getString("angle"), root.getString("hook"),
            root.getJSONArray("clips").objects().map { segment ->
                val source = segment.getJSONObject("source")
                ReelClipSegment(SourceClip(source.getString("id"), source.getString("uri"), source.getLong("duration"), source.getInt("width"), source.getInt("height")),
                    segment.getLong("trimStart"), segment.getLong("trimEnd"), segment.getLong("outputStart"))
            },
            root.getJSONArray("beats").objects().map { TextBeat(it.getString("text"), it.getLong("start"), it.getLong("end"), it.getJSONArray("emphasis").strings()) },
            decodeStyle(style, version),
            Pacing.valueOf(root.getString("pacing")), root.getString("payoff"), if (root.isNull("quality")) null else root.getDouble("quality"),
            GenerationMetadata(meta.getString("planner"), meta.getInt("seed"), meta.getLong("created"), meta.getJSONArray("notes").strings(), if (meta.isNull("fallback")) null else meta.getString("fallback")),
            version = version
        ).validated()
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun decodeStyle(style: JSONObject, version: Int): TextStyle {
        if (version == 1) return TextStyle(TypographyPreset.CLEAN, style.optDouble("fontSize", 64.0).toFloat())
        val safe = style.optJSONObject("safe") ?: JSONObject()
        return TextStyle(
            preset = runCatching { TypographyPreset.valueOf(style.optString("preset", "CLEAN")) }.getOrDefault(TypographyPreset.CLEAN),
            fontSizePx = style.optDouble("fontSize", 64.0).toFloat(),
            typeface = style.optString("typeface", "sans-serif"),
            alignment = runCatching { TextAlignment.valueOf(style.optString("alignment", "CENTER")) }.getOrDefault(TextAlignment.CENTER),
            treatment = runCatching { TextTreatment.valueOf(style.optString("treatment", "BACKDROP")) }.getOrDefault(TextTreatment.BACKDROP),
            foregroundColor = style.optInt("foreground", 0xffffffff.toInt()), emphasisColor = style.optInt("emphasisColor", 0xffffd166.toInt()),
            lineCount = style.optInt("lineCount", 0), placementX = style.optDouble("placementX", .5).toFloat(), placementY = style.optDouble("placementY", .5).toFloat(),
            safeArea = SafeAreaConfig(safe.optDouble("left", .10).toFloat(), safe.optDouble("top", .13).toFloat(), safe.optDouble("right", .10).toFloat(), safe.optDouble("bottom", .16).toFloat(), safe.optDouble("controls", .08).toFloat())
        )
    }
}

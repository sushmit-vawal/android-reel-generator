package com.reelgenerator.trend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TrendCache(context: Context) {
    private val prefs = context.getSharedPreferences("trend-cache", Context.MODE_PRIVATE)
    fun save(profile: TrendProfile) { prefs.edit().putString(key(profile.query), encode(profile)).apply() }
    fun load(query: TrendQuery): TrendProfile? = prefs.getString(key(query), null)?.let { runCatching { decode(it) }.getOrNull() }
    private fun key(q: TrendQuery) = "${q.region}:${q.category?.name ?: "ALL"}:${q.windowDays}"
    private fun encode(p: TrendProfile) = JSONObject().apply {
        put("region", p.query.region); put("category", p.query.category?.name ?: JSONObject.NULL); put("window", p.query.windowDays); put("refreshed", p.refreshedAt); put("expires", p.expiresAt)
        put("items", JSONArray().apply { p.insights.forEach { i -> put(JSONObject().apply { put("id", i.id); put("topic", i.topic); put("category", i.category?.name ?: JSONObject.NULL); put("source", i.source); put("observed", i.observedAt); put("confidence", i.confidence); put("reliability", i.reliability); put("beats", i.beatCount); put("pacing", i.pacing); put("story", i.storyPattern) }) } })
    }.toString()
    private fun decode(raw: String): TrendProfile { val o = JSONObject(raw); val category = if (o.isNull("category")) null else com.reelgenerator.ReelCategory.valueOf(o.getString("category")); val q = TrendQuery(o.getString("region"), category, o.getInt("window")); val a = o.getJSONArray("items"); val items = (0 until a.length()).map { x -> val i = a.getJSONObject(x); TrendInsight(i.getString("id"), i.getString("topic"), category, source = i.getString("source"), observedAt = i.getLong("observed"), confidence = i.getDouble("confidence"), reliability = i.getDouble("reliability"), beatCount = i.getInt("beats"), pacing = i.getString("pacing"), storyPattern = i.getString("story")) }; return TrendProfile(q, items, o.getLong("refreshed"), o.getLong("expires")) }
}

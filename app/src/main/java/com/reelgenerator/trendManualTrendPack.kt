package com.reelgenerator.trend

import android.content.Context
import android.net.Uri
import com.reelgenerator.ReelCategory
import org.json.JSONObject

object ManualTrendPack {
    fun importJson(context: Context, uri: Uri, query: TrendQuery): TrendRefreshResult = try {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return TrendRefreshResult("manual", TrendProviderStatus.MALFORMED, 0, "Unreadable pack")
        val array = JSONObject(raw).optJSONArray("insights") ?: return TrendRefreshResult("manual", TrendProviderStatus.MALFORMED, 0, "Expected insights array")
        val items = (0 until array.length()).map { i -> val o = array.getJSONObject(i); TrendInsight(o.optString("id", "manual-$i"), o.getString("topic"), query.category, source = "manual", observedAt = System.currentTimeMillis(), confidence = o.optDouble("confidence", .7), reliability = 1.0, beatCount = o.optInt("beatCount", 1), pacing = o.optString("pacing", "steady"), storyPattern = o.optString("storyPattern", "observation")) }
        TrendCache(context).save(TrendProfile(query, items, System.currentTimeMillis(), System.currentTimeMillis() + query.windowDays * 86_400_000L)); TrendRefreshResult("manual", TrendProviderStatus.AVAILABLE, items.size)
    } catch (e: Exception) { TrendRefreshResult("manual", TrendProviderStatus.MALFORMED, 0, e.message) }
}

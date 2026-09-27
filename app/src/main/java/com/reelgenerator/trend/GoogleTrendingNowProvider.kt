package com.reelgenerator.trend

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GoogleTrendingNowProvider(private val context: Context) : TrendProvider {
    override val id = "google-trending-now"
    override suspend fun refresh(query: TrendQuery): TrendRefreshResult {
        return try {
        val region = query.region.uppercase().take(2)
        val url = URL("https://trends.google.com/trending/rss?geo=$region")
        val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000; requestMethod = "GET" }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val titles = Regex("<ht:approx_traffic>.*?</item>|<title>([^<]+)</title>").findAll(body).mapNotNull { it.groups[1]?.value }.drop(1).take(50).toList()
        if (titles.isEmpty()) return TrendRefreshResult(id, TrendProviderStatus.MALFORMED, 0, "No trend items")
        val now = System.currentTimeMillis(); TrendCache(context).save(TrendProfile(query, titles.mapIndexed { i, title -> TrendInsight("google-$i", title, query.category, source = id, observedAt = now, confidence = .55, reliability = .7) }, now, now + query.windowDays * 86_400_000L)); TrendRefreshResult(id, TrendProviderStatus.AVAILABLE, titles.size)
        } catch (e: Exception) { TrendRefreshResult(id, TrendProviderStatus.OFFLINE, 0, e.message) }
    }
    override fun cached(query: TrendQuery) = TrendCache(context).load(query)
}

package com.reelgenerator.trend

import com.reelgenerator.ReelCategory

data class TrendQuery(val region: String, val category: ReelCategory?, val windowDays: Int)
data class TrendInsight(val id: String, val topic: String, val category: ReelCategory?, val hookRange: IntRange = 3..12,
    val beatCount: Int = 1, val pacing: String = "steady", val storyPattern: String = "observation",
    val preset: String? = null, val placement: String? = null, val source: String, val observedAt: Long,
    val confidence: Double = .5, val reliability: Double = .5)
data class TrendProfile(val query: TrendQuery, val insights: List<TrendInsight>, val refreshedAt: Long, val expiresAt: Long)
data class TrendRefreshResult(val provider: String, val status: TrendProviderStatus, val itemCount: Int, val error: String? = null)
enum class TrendProviderStatus { AVAILABLE, STALE, OFFLINE, DISABLED, RATE_LIMITED, MALFORMED }
interface TrendProvider { val id: String; suspend fun refresh(query: TrendQuery): TrendRefreshResult; fun cached(query: TrendQuery): TrendProfile? }
interface ReferenceStructureProvider { suspend fun import(uri: String): TrendRefreshResult }

object TrendProfileMerger {
    fun merge(query: TrendQuery, profiles: List<TrendProfile>, now: Long = System.currentTimeMillis()): TrendProfile {
        val insights = profiles.flatMap { it.insights }.groupBy { it.topic.lowercase() }.values.map { group ->
            group.maxByOrNull { it.confidence * it.reliability }!!
        }.sortedByDescending { it.confidence * it.reliability }.take(50)
        return TrendProfile(query, insights, now, now + query.windowDays * 86_400_000L)
    }
}

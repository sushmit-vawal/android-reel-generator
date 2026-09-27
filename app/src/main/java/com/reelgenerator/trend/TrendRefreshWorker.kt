package com.reelgenerator.trend

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelgenerator.ReelCategory

class TrendRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val region = java.util.Locale.getDefault().country.ifBlank { "IN" }
        val result = GoogleTrendingNowProvider(applicationContext).refresh(TrendQuery(region, null, 1))
        return if (result.status == TrendProviderStatus.AVAILABLE) Result.success() else Result.retry()
    }
}

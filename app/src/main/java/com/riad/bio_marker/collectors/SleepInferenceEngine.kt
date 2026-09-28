package com.riad.bio_marker.collectors

import android.content.Context
import com.riad.bio_marker.core.DailyFeatureStore

/**
 * Sleep inference for the daily-targets feature.
 *
 * Strategy (in order):
 *  1. If today's [DailyFeatureStore.Daily.sleepDurationHrs] is already
 *     populated by [com.riad.bio_marker.worker.DailyAggregationWorker]
 *     (which fuses motion energy + screen state via [SleepEstimator]),
 *     we use that — no double inference.
 *  2. Otherwise we ask [SleepEstimator] directly for last night's estimate.
 *  3. Final fallback is 0f (the rule engine then flags sleep as LESS, which
 *     is the correct conservative behaviour when we can't measure sleep).
 *
 * This wrapper exists so future Health Connect / wearable integration
 * has a single seam to plug into without touching workers or the UI.
 */
class SleepInferenceEngine(private val context: Context) {

    enum class Source { DAILY_FEATURE_STORE, SLEEP_ESTIMATOR, NONE }

    data class Result(
        val durationHrs: Float,
        val bedtimeMs: Long?,
        val wakeMs: Long?,
        val source: Source,
    )

    fun estimateForLastNight(
        dailyFeatureStore: DailyFeatureStore,
        endMs: Long = System.currentTimeMillis(),
    ): Result {
        // 1. DailyFeatureStore (already fused with accelerometer + screen).
        val today = dailyFeatureStore.load(dailyFeatureStore.todayKey())
        if (today != null && today.sleepDurationHrs > 0f) {
            return Result(today.sleepDurationHrs, null, null, Source.DAILY_FEATURE_STORE)
        }
        // 2. Direct SleepEstimator.
        val est = try {
            SleepEstimator(context).estimateForLastNight(endMs)
        } catch (_: Throwable) {
            null
        }
        if (est != null && est.durationHrs > 0f) {
            return Result(est.durationHrs, est.bedtimeMs, est.wakeMs, Source.SLEEP_ESTIMATOR)
        }
        return Result(0f, null, null, Source.NONE)
    }
}


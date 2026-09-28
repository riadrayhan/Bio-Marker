package com.riad.bio_marker.ml.insights

import com.riad.bio_marker.core.DailySummaryStore.Summary

/**
 * Personalised target suggester.
 *
 * v1 algorithm: simple linear regression of each metric's actual value vs day
 * index over the last N days, projected one day ahead and clamped to a
 * sensible range. With <7 days of data we fall back to the trimmed mean.
 *
 * Output is the model's *recommendation* — the user can ignore or accept it
 * via the Goal editor. The companion Python script
 * `model_training/train_target_regression.py` produces a TFLite regression
 * model with the same input contract (last-7-day means + DOW one-hot) for
 * future server-trained versions.
 */
class TargetSuggester {

    data class Suggested(
        val sleepHrs: Float,
        val walkMin: Int,
        val phoneHrs: Float,
        val talkMin: Int,
    )

    fun suggest(history: List<Summary>): Suggested? {
        if (history.size < 3) return null
        val sleep = trendNext(history.map { it.sleepActualHrs })
            .coerceIn(5f, 10f)
        val walk = trendNext(history.map { it.walkingActualMin.toFloat() })
            .coerceIn(10f, 120f)
        val phone = trendNext(history.map { it.phoneUsageActualHrs })
            .coerceIn(1f, 8f)
        val talk = trendNext(history.map { it.talkingActualMin.toFloat() })
            .coerceIn(5f, 90f)
        return Suggested(
            sleepHrs = round1(sleep),
            walkMin = walk.toInt(),
            phoneHrs = round1(phone),
            talkMin = talk.toInt(),
        )
    }

    private fun trendNext(series: List<Float>): Float {
        val n = series.size
        if (n < 3) return series.average().toFloat()
        // Simple OLS: y = a + b*x where x is day index.
        val xs = (0 until n).map { it.toDouble() }
        val ys = series.map { it.toDouble() }
        val xMean = xs.average(); val yMean = ys.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - xMean) * (ys[i] - yMean)
            den += (xs[i] - xMean) * (xs[i] - xMean)
        }
        val b = if (den == 0.0) 0.0 else num / den
        val a = yMean - b * xMean
        // Project one day ahead.
        return (a + b * n).toFloat()
    }

    private fun round1(v: Float): Float = (kotlin.math.round(v * 10f) / 10f)
}


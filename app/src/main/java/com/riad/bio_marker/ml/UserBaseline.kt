package com.riad.bio_marker.ml

import android.content.Context
import kotlin.math.sqrt

/**
 * Per-user, per-feature exponential-moving baseline (mean & variance).
 *
 * This is the **personalization** layer: each user gets their own normal-range
 * automatically learned on-device. Used for:
 *  - Z-score anomaly detection in [AnomalyDetector]
 *  - Input normalization before TFLite inference in [TFLiteRiskEngine]
 *
 * Welford-style online variance, persisted to SharedPreferences.
 * Nothing is sent off-device.
 */
class UserBaseline(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_baseline", Context.MODE_PRIVATE)

    /** Update baseline with a new observation. alpha controls how fast it adapts. */
    fun observe(featureKey: String, value: Float, alpha: Float = DEFAULT_ALPHA) {
        val n = prefs.getInt(k(featureKey, "n"), 0)
        val mean = prefs.getFloat(k(featureKey, "mean"), 0f)
        val varc = prefs.getFloat(k(featureKey, "var"), 1f)

        val newMean: Float
        val newVar: Float
        if (n < WARMUP_SAMPLES) {
            // Plain running average until we have enough samples.
            val n1 = n + 1
            newMean = mean + (value - mean) / n1
            val delta = value - mean
            val delta2 = value - newMean
            newVar = if (n == 0) 1f else (varc * n + delta * delta2) / n1
        } else {
            // Exponential moving average — adapts to slow drift.
            newMean = (1 - alpha) * mean + alpha * value
            val diff = value - newMean
            newVar = (1 - alpha) * varc + alpha * diff * diff
        }

        prefs.edit()
            .putInt(k(featureKey, "n"), (n + 1).coerceAtMost(Int.MAX_VALUE - 1))
            .putFloat(k(featureKey, "mean"), newMean)
            .putFloat(k(featureKey, "var"), newVar.coerceAtLeast(MIN_VAR))
            .apply()
    }

    fun mean(featureKey: String): Float = prefs.getFloat(k(featureKey, "mean"), 0f)
    fun std(featureKey: String): Float = sqrt(prefs.getFloat(k(featureKey, "var"), 1f))
    fun count(featureKey: String): Int = prefs.getInt(k(featureKey, "n"), 0)

    /** z-score of [value] against this user's personal baseline. */
    fun zScore(featureKey: String, value: Float): Float {
        val s = std(featureKey).coerceAtLeast(MIN_STD)
        return (value - mean(featureKey)) / s
    }

    fun isWarm(featureKey: String): Boolean = count(featureKey) >= WARMUP_SAMPLES

    fun clear() = prefs.edit().clear().apply()

    private fun k(feature: String, field: String) = "$feature.$field"

    companion object {
        const val WARMUP_SAMPLES = 200
        private const val DEFAULT_ALPHA = 0.01f
        private const val MIN_VAR = 1e-4f
        private const val MIN_STD = 1e-2f
    }
}


package com.riad.bio_marker.ml.insights

import com.riad.bio_marker.core.DailySummaryStore.Summary
import kotlin.math.sqrt

/**
 * Trend anomaly detector over the last 30 days of [Summary] rows.
 *
 * v1 algorithm: per-metric z-score on the last 30 days. If yesterday's value
 * is more than [threshold] standard deviations from the 30-day mean for ≥1
 * metric, we mark the day as anomalous and return the offending metrics.
 *
 * The Python training script `model_training/train_anomaly_ae.py` produces a
 * compatible TFLite autoencoder. When the asset is present a future revision
 * can swap [score] for the model output (input shape `[1, 4]`, output =
 * reconstruction error). The Kotlin fallback ships in v1 so the app works
 * out-of-the-box on first install with no asset dependency.
 */
class AnomalyDetector(private val threshold: Float = 2.0f) {

    data class Output(
        val anomalyScore: Float,
        val anomalous: Boolean,
        val anomalousMetrics: List<String>,
    )

    fun analyze(history: List<Summary>): Output {
        if (history.size < 5) return Output(0f, false, emptyList())
        val today = history.last()
        val priors = history.dropLast(1)

        val (sleepZ, sleepBad) = z(priors.map { it.sleepActualHrs }, today.sleepActualHrs)
        val (walkZ, _) = z(priors.map { it.walkingActualMin.toFloat() }, today.walkingActualMin.toFloat())
        val (phoneZ, _) = z(priors.map { it.phoneUsageActualHrs }, today.phoneUsageActualHrs)
        val (talkZ, _) = z(priors.map { it.talkingActualMin.toFloat() }, today.talkingActualMin.toFloat())

        val flagged = mutableListOf<String>()
        // Direction matters: low sleep, low walk, HIGH phone, low talk are concerning.
        if (sleepZ < -threshold) flagged += "sleep"
        if (walkZ < -threshold) flagged += "walking"
        if (phoneZ > threshold) flagged += "phone"
        if (talkZ < -threshold) flagged += "talking"

        // Use the magnitude of the worst metric as the overall score.
        val worst = listOf(-sleepZ, -walkZ, phoneZ, -talkZ).maxOrNull() ?: 0f
        return Output(
            anomalyScore = worst.coerceAtLeast(0f),
            anomalous = flagged.isNotEmpty(),
            anomalousMetrics = flagged,
        )
    }

    private fun z(prior: List<Float>, value: Float): Pair<Float, Boolean> {
        if (prior.isEmpty()) return 0f to false
        val mean = prior.average().toFloat()
        val varSum = prior.sumOf { ((it - mean) * (it - mean)).toDouble() }
        val sd = sqrt(varSum / prior.size).toFloat().coerceAtLeast(1e-3f)
        val zVal = (value - mean) / sd
        return zVal to (kotlin.math.abs(zVal) > threshold)
    }
}


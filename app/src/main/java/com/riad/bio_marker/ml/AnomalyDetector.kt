package com.riad.bio_marker.ml

import kotlin.math.abs

/**
 * Real-time anomaly detector. Pure on-device, no model file needed.
 *
 * Flags a sample as anomalous when its personalized z-score exceeds [zThreshold]
 * AND the user's baseline has finished its warm-up period (so we don't fire
 * during the first few minutes of use).
 */
class AnomalyDetector(
    private val baseline: UserBaseline,
    private val featureKey: String,
    private val zThreshold: Float = 3.0f,
) {

    /** @return true if this sample is anomalous. Always updates the baseline. */
    fun process(value: Float): Boolean {
        val warm = baseline.isWarm(featureKey)
        val z = baseline.zScore(featureKey, value)
        baseline.observe(featureKey, value)
        return warm && abs(z) >= zThreshold
    }
}


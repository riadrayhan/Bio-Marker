package com.riad.bio_marker.ml

import kotlin.math.abs
import kotlin.math.exp

/**
 * Transparent rule-based fallback used when no trained TFLite model is bundled.
 *
 * Score = sigmoid( Σ wᵢ · normalisedFeatureᵢ ).
 *
 * Weights here come from published correlations (RADAR-MDD, GLOBEM 2024) but are
 * NOT clinically validated. This engine is a placeholder so the full app pipeline
 * is exercised end-to-end before a real model is available.
 */
class HeuristicRiskEngine : RiskInferenceEngine {

    override val name: String = "heuristic-v0"

    /** Tuned by the FeatureSpec.NAMES order. Positive weight = increases risk score. */
    private val weights = floatArrayOf(
        -0.6f,  // motion_energy_mean   ↓ → more sedentary → higher risk
        -0.3f,  // motion_energy_std    ↓ → flatter activity rhythm
        +0.4f,  // motion_anomaly_rate
        -0.2f,  // keystroke_count      ↓ → social withdrawal proxy
        +0.5f,  // avg_inter_key_latency ↑ → psychomotor slowing
        +0.6f,  // backspace_rate
        +0.0f,  // hour_sin (handled via interaction in real model)
        +0.0f,  // hour_cos
        -0.3f,  // voice_energy_mean    ↓ → quieter speech
        -0.5f,  // voice_energy_std     ↓ → flat affect / monotone
        -0.4f,  // voice_zcr_mean       ↓ → slower speech rate
        +0.5f,  // voice_pause_ratio    ↑ → more pauses
    )

    override fun infer(features: FeatureVector): RiskResult {
        val v = features.values
        var logit = 0f
        val contribs = FloatArray(v.size)
        for (i in v.indices) {
            val c = weights[i] * v[i]
            contribs[i] = c
            logit += c
        }
        val score = sigmoid(logit)
        val phq9 = (score * 27f).toInt().coerceIn(0, 27)

        val top = contribs
            .mapIndexed { i, c -> FeatureSpec.NAMES[i] to abs(c) }
            .sortedByDescending { it.second }
            .take(3)
            .filter { it.second > 1e-3f }
            .map { it.first }

        return RiskResult(score, phq9, top, name)
    }

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x))).toFloat()
}


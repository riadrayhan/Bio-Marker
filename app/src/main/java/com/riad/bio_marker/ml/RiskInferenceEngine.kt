package com.riad.bio_marker.ml

/**
 * Output of an on-device risk-scoring pass.
 *
 * - [score] is normalised to 0..1 (higher = more concerning behavioural pattern).
 * - [phq9Estimate] is a coarse mapping back to the 0..27 PHQ-9 scale for clinical readability.
 *   This is a **screening estimate, not a diagnosis.**
 * - [topContributors] explains the top features that pushed the score up (basic SHAP-lite).
 * - [engineName] tells the UI whether a real model or the heuristic fallback was used.
 */
data class RiskResult(
    val score: Float,
    val phq9Estimate: Int,
    val topContributors: List<String>,
    val engineName: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

interface RiskInferenceEngine {
    val name: String
    fun infer(features: FeatureVector): RiskResult
    fun close() = Unit
}


package com.riad.bio_marker.ml

import android.content.Context

/**
 * Facade the UI calls to get a fresh [RiskResult]. Picks the TFLite engine if a
 * bundled model exists, otherwise transparently falls back to the heuristic.
 */
class RiskScorer(
    context: Context,
    private val baseline: UserBaseline,
    private val voiceCache: VoiceFeatureCache,
) {
    private val engine: RiskInferenceEngine =
        TFLiteRiskEngine.tryCreate(context, baseline) ?: HeuristicRiskEngine()

    private val extractor = FeatureExtractor(baseline, voiceCache)

    val engineName: String get() = engine.name

    fun score(snapshot: com.riad.bio_marker.core.MetricsBus.Snapshot): RiskResult {
        val features = extractor.extract(snapshot)
        return engine.infer(features)
    }

    fun close() = engine.close()
}


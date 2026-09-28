package com.riad.bio_marker.ml

/**
 * Stable, ordered feature vector consumed by every [RiskInferenceEngine].
 * Order MUST match the trained model's input layer.
 *
 * To add a feature later: append to [NAMES] and re-train; never reorder existing entries.
 */
object FeatureSpec {
    val NAMES: List<String> = listOf(
        "motion_energy_mean",     // mean linear acc magnitude (m/s²) over today
        "motion_energy_std",      // std-dev of motion energy
        "motion_anomaly_rate",    // anomalies / total samples today
        "keystroke_count",        // total keystrokes today (log-scaled before model)
        "avg_inter_key_latency",  // ms
        "backspace_rate",         // 0..1
        "hour_sin",               // circadian feature: sin(2π·hour/24)
        "hour_cos",               // circadian feature: cos(2π·hour/24)
        "voice_energy_mean",      // mean RMS energy of recent voice check-in
        "voice_energy_std",       // prosody flatness proxy (low = depression)
        "voice_zcr_mean",         // zero-crossing rate, speech-rate proxy
        "voice_pause_ratio",      // fraction of frames below silence threshold
    )

    const val SIZE: Int = 12
    init { require(NAMES.size == SIZE) }
}

data class FeatureVector(val values: FloatArray) {
    init { require(values.size == FeatureSpec.SIZE) }
    fun named(): Map<String, Float> =
        FeatureSpec.NAMES.zip(values.toList()).toMap()
}


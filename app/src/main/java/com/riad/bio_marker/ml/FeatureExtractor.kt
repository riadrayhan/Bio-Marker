package com.riad.bio_marker.ml

import com.riad.bio_marker.core.MetricsBus
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Builds a [FeatureVector] from the live [MetricsBus] snapshot + per-user baseline state.
 *
 * For Slice 2 this is a simple cross-section of "today so far". A windowed
 * 7-day rolling extractor reading from the daily aggregate JSON files will land
 * in Slice 3 alongside an LSTM model.
 */
class FeatureExtractor(
    private val baseline: UserBaseline,
    private val voiceCache: VoiceFeatureCache,
) {

    fun extract(snapshot: MetricsBus.Snapshot): FeatureVector {
        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).toFloat()
        val angle = (2.0 * Math.PI * nowHour / 24.0).toFloat()

        // motion_energy_mean: pull from baseline if warm enough, else use the latest reading
        val motionMean = if (baseline.isWarm(FeatureSpec.NAMES[0]))
            baseline.mean(FeatureSpec.NAMES[0])
        else snapshot.recentMotionEnergy

        val motionStd = if (baseline.isWarm(FeatureSpec.NAMES[1]))
            baseline.std(FeatureSpec.NAMES[0])      // std of the energy feature
        else 0f

        val anomalyRate = if (snapshot.motionSamplesToday > 0)
            snapshot.motionAnomaliesToday.toFloat() / snapshot.motionSamplesToday
        else 0f

        val keyCountLog = ln(1f + snapshot.keystrokesToday.toFloat())
        val avgLatency = snapshot.avgInterKeyLatencyMs.toFloat()
        val backspaceRate = if (snapshot.keystrokesToday > 0)
            snapshot.backspacesToday.toFloat() / snapshot.keystrokesToday
        else 0f

        val voice = voiceCache.load()
        val voiceEnergyMean = voice?.energyMean ?: 0f
        val voiceEnergyStd = voice?.energyStd ?: 0f
        val voiceZcr = voice?.zcrMean ?: 0f
        val voicePauseRatio = voice?.pauseRatio ?: 0f

        val v = floatArrayOf(
            motionMean,
            motionStd,
            anomalyRate,
            keyCountLog,
            avgLatency,
            backspaceRate,
            sin(angle),
            cos(angle),
            voiceEnergyMean,
            voiceEnergyStd,
            voiceZcr,
            voicePauseRatio,
        )

        // Update the baselines for normalisable features (not the circadian ones).
        baseline.observe(FeatureSpec.NAMES[0], motionMean)
        baseline.observe(FeatureSpec.NAMES[3], keyCountLog)
        baseline.observe(FeatureSpec.NAMES[4], avgLatency)
        // Voice arrives sparsely — only feed real samples (non-zero timestamp)
        // into the baseline so we don't poison the mean with default 0s.
        if (voice != null) {
            baseline.observe(FeatureSpec.NAMES[8], voiceEnergyMean)
            baseline.observe(FeatureSpec.NAMES[9], voiceEnergyStd)
            baseline.observe(FeatureSpec.NAMES[10], voiceZcr)
            baseline.observe(FeatureSpec.NAMES[11], voicePauseRatio)
        }

        return FeatureVector(v)
    }
}


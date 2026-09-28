package com.riad.bio_marker.monitoring

import com.riad.bio_marker.core.MetricsBus
import com.riad.bio_marker.ml.UserBaseline
import com.riad.bio_marker.ml.VoiceFeatureCache
import kotlin.math.exp
import kotlin.math.ln

/**
 * Pro-level estimator: turns the passive sensor / typing / voice data collected
 * during a check-in window into PHQ-9 (depression) and GAD-7 (anxiety) screening
 * totals, plus a confidence rating and the top contributing features.
 *
 * These estimates are SCREENING SIGNALS, not diagnoses.
 *
 * Improvements over v1:
 *  - Uses **window-only** counters (subtracts the baseline captured at window
 *    start) so rates aren't polluted by data from earlier in the day.
 *  - Normalizes counters by the actual window duration → keystrokes/min,
 *    motion-energy/sample etc. instead of raw totals.
 *  - When the user's [UserBaseline] is warm, it uses **personal z-scores**
 *    via a logistic activation, so "slow typing" means slow *for this user*.
 *    Falls back to fixed thresholds during the warm-up period.
 *  - **Coverage gating**: each component has a 0..1 coverage weight; missing
 *    data drops out of the weighted mean instead of being treated as zero.
 *  - **Voice freshness gate**: voice features older than 6 h before the window
 *    started are excluded from coverage instead of counted as flat affect.
 *  - **Robust model blend**: TFLite live risk only nudges the score when motion
 *    + typing coverage are both present.
 *  - **Calibration-aware mapping**: piecewise-linear anchors to PHQ-9 and GAD-7
 *    that line up with the standard Kroenke / Spitzer cut-points (5/10/15/20
 *    and 5/10/15) so the verdict band is internally consistent.
 *  - **Top contributors** for "Why this verdict" UI.
 */
object WindowResultEstimator {

    enum class Confidence { LOW, MODERATE, HIGH }

    data class Estimate(
        val phq9: Int,
        val gad7: Int,
        val confidence: Confidence = Confidence.LOW,
        val topDepressionContributors: List<String> = emptyList(),
        val topAnxietyContributors: List<String> = emptyList(),
    )

    // Stable feature keys, mapped to localized strings in the UI layer.
    const val FEAT_SEDENTARINESS = "sedentariness"
    const val FEAT_SLOW_TYPING = "slow_typing"
    const val FEAT_CONCENTRATION = "concentration"
    const val FEAT_WITHDRAWAL = "withdrawal"
    const val FEAT_RESTLESSNESS = "restlessness"
    const val FEAT_VOICE_FLAT = "voice_flat"

    // Personal-baseline keys.
    private const val BL_MOTION_ENERGY = "motion_energy_mean"
    private const val BL_LATENCY = "avg_inter_key_latency"
    private const val BL_KEYSTROKES = "keystroke_count_log"

    fun estimate(
        snapshot: MetricsBus.Snapshot,
        voice: VoiceFeatureCache.Snapshot?,
        liveRiskScore: Float?,
        baseline: UserBaseline,
        session: MonitoringSession,
        windowBaseline: MonitoringStore.WindowBaseline,
        nowMs: Long = System.currentTimeMillis(),
    ): Estimate {
        // ---------- Window-only deltas ----------
        val windowSec = ((nowMs - session.startedAtMs).coerceAtLeast(60_000L)) / 1000f
        val windowMin = windowSec / 60f
        val motionSamples = (snapshot.motionSamplesToday - windowBaseline.motionSamples).coerceAtLeast(0)
        val motionAnoms = (snapshot.motionAnomaliesToday - windowBaseline.motionAnomalies).coerceAtLeast(0)
        val keystrokes = (snapshot.keystrokesToday - windowBaseline.keystrokes).coerceAtLeast(0)
        val backspaces = (snapshot.backspacesToday - windowBaseline.backspaces).coerceAtLeast(0)
        // Mean motion energy *over the entire window* — far more reliable than
        // the single most-recent reading.
        val motionEnergySumWindow = (snapshot.motionEnergySumToday
            - windowBaseline.motionEnergySum).coerceAtLeast(0.0)
        val motionEnergyMean = if (motionSamples > 0)
            (motionEnergySumWindow / motionSamples).toFloat()
        else 0f

        // ---------- Coverage per stream (0..1) ----------
        val motionCoverage = clamp01(motionSamples / (windowSec / 5f))      // ≥ 1 sample / 5 s
        val typingCoverage = clamp01(keystrokes / (windowMin * 5f).coerceAtLeast(1f)) // ≥ 5 keys/min
        val voiceFresh = voice != null &&
            voice.timestampMs >= session.startedAtMs - 6L * 60 * 60 * 1000
        val voiceCoverage = if (voiceFresh) 1f else 0f
        val modelCoverage = if (liveRiskScore != null &&
            (motionCoverage + typingCoverage) >= 0.5f
        ) 1f else 0f

        // ---------- Components (0..1, higher = more concerning) ----------
        // Use the WINDOW MEAN energy (not the latest sample). For an idle phone
        // this is still small, but at least it represents the whole hour, and
        // the data-sufficiency shrinkage below will down-weight it.
        val sedentariness = if (baseline.isWarm(BL_MOTION_ENERGY)) {
            sigmoid(-baseline.zScore(BL_MOTION_ENERGY, motionEnergyMean))
        } else {
            clamp01(1f - motionEnergyMean / 1.2f)
        }

        val latency = snapshot.avgInterKeyLatencyMs.toFloat()
        val slowTyping = when {
            latency <= 0f -> 0f
            baseline.isWarm(BL_LATENCY) -> sigmoid(baseline.zScore(BL_LATENCY, latency))
            else -> clamp01((latency - 220f) / 280f)
        }

        val backspaceRate = if (keystrokes > 0)
            backspaces.toFloat() / keystrokes else 0f
        val concentrationStruggle = sigmoid(8f * (backspaceRate - 0.10f))

        val keysPerMin = if (windowMin > 0f) keystrokes / windowMin else 0f
        val withdrawal = if (baseline.isWarm(BL_KEYSTROKES)) {
            sigmoid(-baseline.zScore(BL_KEYSTROKES, ln(1f + keystrokes.toFloat())))
        } else {
            clamp01(1f - keysPerMin / 30f)
        }

        val anomalyRate = if (motionSamples > 0)
            motionAnoms.toFloat() / motionSamples else 0f
        val restlessness = sigmoid(20f * (anomalyRate - 0.04f))

        val voiceFlatness = if (voice != null && voiceFresh) {
            val pause = voice.pauseRatio.coerceIn(0f, 1f)
            val estd = voice.energyStd.coerceIn(0f, 1f)
            sigmoid(2f * (pause - 0.5f) - 2f * (estd - 0.2f))
        } else 0f

        // ---------- Coverage-weighted weighted mean per scale ----------
        val depWeights = listOf(
            0.22f * motionCoverage,  // sedentariness
            0.18f * typingCoverage,  // slow typing
            0.10f * typingCoverage,  // concentration
            0.14f * typingCoverage,  // withdrawal
            0.06f * motionCoverage,  // restlessness
            0.20f * voiceCoverage,   // voice flat
        )
        val depComponents = listOf(
            sedentariness, slowTyping, concentrationStruggle,
            withdrawal, restlessness, voiceFlatness,
        )
        // The component values themselves are scale-agnostic; only the WEIGHTS
        // differ between depression and anxiety, so we reuse depComponents.
        val depRaw = weightedMean(depComponents, depWeights)

        val anxWeights = listOf(
            0.06f * motionCoverage,
            0.12f * typingCoverage,
            0.22f * typingCoverage,
            0.06f * typingCoverage,
            0.28f * motionCoverage,
            0.10f * voiceCoverage,
        )
        val anxRaw = weightedMean(depComponents, anxWeights)

        // ---------- Data-sufficiency shrinkage ----------
        // Without this, a window that only has motion data will have its weight
        // sum renormalised so a single noisy sedentariness signal looks like a
        // *full* PHQ-9 score. Shrink toward the neutral 0 (= "no signal") by
        // the fraction of total possible weight we actually had coverage for.
        val depMaxWeight = 0.22f + 0.18f + 0.10f + 0.14f + 0.06f + 0.20f   // = 0.90
        val anxMaxWeight = 0.06f + 0.12f + 0.22f + 0.06f + 0.28f + 0.10f   // = 0.84
        val depCoverage = (depWeights.sum() / depMaxWeight).coerceIn(0f, 1f)
        val anxCoverage = (anxWeights.sum() / anxMaxWeight).coerceIn(0f, 1f)
        val depShrunk = depRaw * depCoverage
        val anxShrunk = anxRaw * anxCoverage

        // ---------- Robust live-model blend ----------
        val blendW = 0.35f * modelCoverage
        val live = liveRiskScore ?: 0f
        val depBlended = clamp01((1f - blendW) * depShrunk + blendW * live)
        val anxBlended = clamp01((1f - blendW) * anxShrunk + blendW * live)

        // ---------- Calibration-aware mapping ----------
        val phq9 = piecewiseLinear(depBlended, ANCHORS_PHQ9).toInt().coerceIn(0, 27)
        val gad7 = piecewiseLinear(anxBlended, ANCHORS_GAD7).toInt().coerceIn(0, 21)

        // ---------- Confidence ----------
        val durationCoverage = windowSec / (windowSec + 3600f)
        val confValue = clamp01(
            0.40f * motionCoverage +
                0.35f * typingCoverage +
                0.15f * voiceCoverage +
                0.10f * durationCoverage
        )
        val confidence = when {
            confValue >= 0.70f -> Confidence.HIGH
            confValue >= 0.40f -> Confidence.MODERATE
            else -> Confidence.LOW
        }

        // ---------- Top contributors ----------
        val featKeys = listOf(
            FEAT_SEDENTARINESS, FEAT_SLOW_TYPING, FEAT_CONCENTRATION,
            FEAT_WITHDRAWAL, FEAT_RESTLESSNESS, FEAT_VOICE_FLAT,
        )
        val depRanked = featKeys.zip(depWeights.zip(depComponents) { w, c -> w * c })
            .sortedByDescending { it.second }
            .filter { it.second > 0.02f }
            .take(3)
            .map { it.first }
        val anxRanked = featKeys.zip(anxWeights.zip(depComponents) { w, c -> w * c })
            .sortedByDescending { it.second }
            .filter { it.second > 0.02f }
            .take(3)
            .map { it.first }

        return Estimate(
            phq9 = phq9,
            gad7 = gad7,
            confidence = confidence,
            topDepressionContributors = depRanked,
            topAnxietyContributors = anxRanked,
        )
    }

    // ----- helpers -----

    private val ANCHORS_PHQ9 = listOf(
        0.00f to 0f, 0.20f to 5f, 0.40f to 10f,
        0.60f to 15f, 0.80f to 20f, 1.00f to 27f,
    )
    private val ANCHORS_GAD7 = listOf(
        0.00f to 0f, 0.25f to 5f, 0.50f to 10f,
        0.75f to 15f, 1.00f to 21f,
    )

    private fun piecewiseLinear(x: Float, anchors: List<Pair<Float, Float>>): Float {
        val xc = x.coerceIn(0f, 1f)
        for (i in 0 until anchors.size - 1) {
            val (x0, y0) = anchors[i]
            val (x1, y1) = anchors[i + 1]
            if (xc <= x1) {
                if (x1 == x0) return y0
                val t = (xc - x0) / (x1 - x0)
                return y0 + t * (y1 - y0)
            }
        }
        return anchors.last().second
    }

    private fun weightedMean(values: List<Float>, weights: List<Float>): Float {
        var num = 0f
        var den = 0f
        for (i in values.indices) {
            num += values[i] * weights[i]
            den += weights[i]
        }
        return if (den < 1e-3f) 0f else (num / den).coerceIn(0f, 1f)
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x.toDouble()).toFloat())
    private fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)
}

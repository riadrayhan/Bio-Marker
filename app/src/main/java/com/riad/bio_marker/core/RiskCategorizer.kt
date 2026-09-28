package com.riad.bio_marker.core

import android.content.Context
import androidx.core.content.ContextCompat
import com.riad.bio_marker.R

/**
 * Maps numeric risk scores and PHQ-9-scale numbers to user-friendly verdict
 * strings + colors. The dashboard shows ONLY these verdicts — never raw
 * percentages or scores — to avoid pseudo-precision in a non-medical-device app.
 */
object RiskCategorizer {

    enum class Level { EXCELLENT, GOOD, FAIR, CONCERNING, HIGH }

    fun fromRiskScore(score: Float): Level = when {
        score < 0.20f -> Level.EXCELLENT
        score < 0.40f -> Level.GOOD
        score < 0.60f -> Level.FAIR
        score < 0.80f -> Level.CONCERNING
        else          -> Level.HIGH
    }

    /** Standard PHQ-9 banding (Kroenke 2001). */
    fun fromPhq9(total: Int): Level = when {
        total <= 4   -> Level.EXCELLENT     // minimal
        total <= 9   -> Level.GOOD          // mild
        total <= 14  -> Level.FAIR          // moderate
        total <= 19  -> Level.CONCERNING    // moderately severe
        else         -> Level.HIGH          // severe
    }

    /** Standard GAD-7 banding (Spitzer 2006). */
    fun fromGad7(total: Int): Level = when {
        total <= 4   -> Level.EXCELLENT
        total <= 9   -> Level.GOOD
        total <= 14  -> Level.FAIR
        else         -> Level.CONCERNING
    }

    fun label(context: Context, level: Level): String = context.getString(
        when (level) {
            Level.EXCELLENT  -> R.string.verdict_excellent
            Level.GOOD       -> R.string.verdict_good
            Level.FAIR       -> R.string.verdict_fair
            Level.CONCERNING -> R.string.verdict_concerning
            Level.HIGH       -> R.string.verdict_high
        }
    )

    fun color(context: Context, level: Level): Int = ContextCompat.getColor(
        context,
        when (level) {
            Level.EXCELLENT  -> R.color.verdict_excellent
            Level.GOOD       -> R.color.verdict_good
            Level.FAIR       -> R.color.verdict_fair
            Level.CONCERNING -> R.color.verdict_concerning
            Level.HIGH       -> R.color.verdict_high
        }
    )

    /** Human label for activity from motion-energy mean. */
    fun activityLabel(context: Context, motionMean: Float): String = context.getString(
        when {
            motionMean < 0.2f -> R.string.activity_sedentary
            motionMean < 0.6f -> R.string.activity_light
            motionMean < 1.2f -> R.string.activity_active
            else              -> R.string.activity_very_active
        }
    )

    /** Human label for typing-speed proxy from inter-key latency. */
    fun typingLabel(context: Context, avgLatencyMs: Long): String = context.getString(
        when {
            avgLatencyMs == 0L  -> R.string.typing_no_data
            avgLatencyMs < 200  -> R.string.typing_fast
            avgLatencyMs < 350  -> R.string.typing_normal
            avgLatencyMs < 500  -> R.string.typing_slow
            else                -> R.string.typing_very_slow
        }
    )

    fun sleepRestlessnessLabel(context: Context, anomalyRate: Float): String = context.getString(
        when {
            anomalyRate < 0.02f -> R.string.calm_steady
            anomalyRate < 0.06f -> R.string.calm_some_variation
            else                -> R.string.calm_restless
        }
    )
}


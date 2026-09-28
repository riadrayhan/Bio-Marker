package com.riad.bio_marker.monitoring

import android.content.Context
import com.riad.bio_marker.core.MetricsBus
import com.riad.bio_marker.core.RiskCategorizer
import com.riad.bio_marker.core.ServiceLocator
import kotlin.math.max

/**
 * A user-defined check-in window. The app keeps collecting passively the whole
 * time, but the dashboard only **reveals a final verdict** when the window ends.
 *
 * Duration is stored in HOURS to support sub-day windows (1 h, 5 h) as well as
 * multi-day windows (24 h, 48 h, 72 h). Convert to days only for display.
 */
data class MonitoringSession(
    val startedAtMs: Long,
    val periodHours: Int,
) {
    val endsAtMs: Long get() = startedAtMs + periodHours * 60L * 60 * 1000

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= endsAtMs
    fun millisRemaining(nowMs: Long = System.currentTimeMillis()): Long = max(0L, endsAtMs - nowMs)
    fun progress(nowMs: Long = System.currentTimeMillis()): Float {
        val total = (endsAtMs - startedAtMs).coerceAtLeast(1L)
        val elapsed = (nowMs - startedAtMs).coerceIn(0L, total)
        return elapsed.toFloat() / total
    }

    /** True if this window is shorter than a day (label says "X-hour" instead of "X-day"). */
    val isHourly: Boolean get() = periodHours < 24

    /** Friendly number to show next to the unit label. */
    val displayValue: Int
        get() = if (isHourly) periodHours else periodHours / 24

    companion object {
        /** Allowed window lengths in hours: 1 h, 3 h, 24 h, 48 h. */
        val SUPPORTED_HOURS = listOf(1, 3, 24, 48)
    }
}

class MonitoringStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_monitoring", Context.MODE_PRIVATE)

    fun current(): MonitoringSession? {
        val started = prefs.getLong(K_STARTED, 0L)
        if (started == 0L) return null
        // Backwards-compat: older builds stored "days" — read it & convert.
        val hours = prefs.getInt(K_HOURS, 0).takeIf { it > 0 }
            ?: (prefs.getInt(K_DAYS_LEGACY, 0) * 24).takeIf { it > 0 }
            ?: return null
        return MonitoringSession(started, hours)
    }

    fun start(periodHours: Int): MonitoringSession {
        val session = MonitoringSession(System.currentTimeMillis(), periodHours)
        // Snapshot the "today" counters so we can compute window-only deltas later.
        val s = ServiceLocator.metricsBus.state.value
        prefs.edit()
            .putLong(K_STARTED, session.startedAtMs)
            .putInt(K_HOURS, session.periodHours)
            .putInt(K_BASE_MOTION_SAMPLES, s.motionSamplesToday)
            .putInt(K_BASE_MOTION_ANOM, s.motionAnomaliesToday)
            .putInt(K_BASE_KEYSTROKES, s.keystrokesToday)
            .putInt(K_BASE_BACKSPACES, s.backspacesToday)
            .putFloat(K_BASE_MOTION_ESUM, s.motionEnergySumToday.toFloat())
            .remove(K_DAYS_LEGACY)
            .remove(K_FROZEN_LEVEL)
            .remove(K_FROZEN_PHQ9)
            .remove(K_FROZEN_GAD7)
            .remove(K_FROZEN_AT)
            .remove(K_FROZEN_CONF)
            .remove(K_FROZEN_DEP_CONTRIB)
            .remove(K_FROZEN_ANX_CONTRIB)
            .apply()
        return session
    }

    /** Counters captured at window start, so the estimator can compute window-only rates. */
    data class WindowBaseline(
        val motionSamples: Int,
        val motionAnomalies: Int,
        val keystrokes: Int,
        val backspaces: Int,
        val motionEnergySum: Float,
    )

    fun windowBaseline(): WindowBaseline = WindowBaseline(
        motionSamples = prefs.getInt(K_BASE_MOTION_SAMPLES, 0),
        motionAnomalies = prefs.getInt(K_BASE_MOTION_ANOM, 0),
        keystrokes = prefs.getInt(K_BASE_KEYSTROKES, 0),
        backspaces = prefs.getInt(K_BASE_BACKSPACES, 0),
        motionEnergySum = prefs.getFloat(K_BASE_MOTION_ESUM, 0f),
    )

    fun cancel() = prefs.edit().clear().apply()

    fun freezeFinalVerdict(level: RiskCategorizer.Level) {
        prefs.edit().putString(K_FROZEN_LEVEL, level.name).apply()
    }

    fun frozenVerdict(): RiskCategorizer.Level? {
        val name = prefs.getString(K_FROZEN_LEVEL, null) ?: return null
        return runCatching { RiskCategorizer.Level.valueOf(name) }.getOrNull()
    }

    fun freezeScreeningEstimate(estimate: WindowResultEstimator.Estimate) {
        prefs.edit()
            .putInt(K_FROZEN_PHQ9, estimate.phq9)
            .putInt(K_FROZEN_GAD7, estimate.gad7)
            .putString(K_FROZEN_CONF, estimate.confidence.name)
            .putString(K_FROZEN_DEP_CONTRIB, estimate.topDepressionContributors.joinToString("|"))
            .putString(K_FROZEN_ANX_CONTRIB, estimate.topAnxietyContributors.joinToString("|"))
            .putLong(K_FROZEN_AT, System.currentTimeMillis())
            .apply()
    }

    /** Returns null if no screening has been frozen for the current/last window. */
    fun frozenScreening(): WindowResultEstimator.Estimate? {
        if (!prefs.contains(K_FROZEN_PHQ9)) return null
        val confName = prefs.getString(K_FROZEN_CONF, null)
        val conf = confName?.let {
            runCatching { WindowResultEstimator.Confidence.valueOf(it) }.getOrNull()
        } ?: WindowResultEstimator.Confidence.LOW
        val dep = prefs.getString(K_FROZEN_DEP_CONTRIB, "")
            ?.takeIf { it.isNotEmpty() }?.split("|") ?: emptyList()
        val anx = prefs.getString(K_FROZEN_ANX_CONTRIB, "")
            ?.takeIf { it.isNotEmpty() }?.split("|") ?: emptyList()
        return WindowResultEstimator.Estimate(
            phq9 = prefs.getInt(K_FROZEN_PHQ9, 0),
            gad7 = prefs.getInt(K_FROZEN_GAD7, 0),
            confidence = conf,
            topDepressionContributors = dep,
            topAnxietyContributors = anx,
        )
    }

    companion object {
        private const val K_STARTED = "started"
        private const val K_HOURS = "hours"
        private const val K_DAYS_LEGACY = "days"
        private const val K_FROZEN_LEVEL = "frozen_level"
        private const val K_FROZEN_PHQ9 = "frozen_phq9"
        private const val K_FROZEN_GAD7 = "frozen_gad7"
        private const val K_FROZEN_AT = "frozen_at"
        private const val K_FROZEN_CONF = "frozen_conf"
        private const val K_FROZEN_DEP_CONTRIB = "frozen_dep_contrib"
        private const val K_FROZEN_ANX_CONTRIB = "frozen_anx_contrib"
        private const val K_BASE_MOTION_SAMPLES = "base_motion_samples"
        private const val K_BASE_MOTION_ANOM = "base_motion_anom"
        private const val K_BASE_KEYSTROKES = "base_keystrokes"
        private const val K_BASE_BACKSPACES = "base_backspaces"
        private const val K_BASE_MOTION_ESUM = "base_motion_esum"
    }
}

fun monitoringStore(): MonitoringStore = ServiceLocator.monitoringStore

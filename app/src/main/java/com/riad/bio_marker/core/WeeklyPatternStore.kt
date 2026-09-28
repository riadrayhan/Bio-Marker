package com.riad.bio_marker.core

import android.content.Context
import java.util.Calendar

/**
 * Aggregates one signal per day-of-week (1=Sun … 7=Sat) for several signals:
 * sleep hours, step count, wellness index. Used by the PATTERN_REFLECTION
 * nudge to surface things like "Sunday nights you sleep ~1.5h less than other
 * nights — anything on your mind?".
 *
 * Stores a running mean + count per (signal, dow). After ≥ 3 samples per DOW
 * we can detect outlier days where one DOW is > 1 SD-equivalent from the
 * average of the others.
 */
class WeeklyPatternStore(context: Context) {

    enum class Signal(val key: String) {
        SLEEP("sleep"),
        STEPS("steps"),
        WELLNESS("wellness"),
    }

    data class Pattern(
        val signal: Signal,
        val dayOfWeek: Int,        // Calendar.SUNDAY..SATURDAY
        val dowMean: Float,
        val otherMean: Float,
        val direction: Direction,  // LOW means dow is lower than average
    )

    enum class Direction { LOW, HIGH }

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_weekly", Context.MODE_PRIVATE)

    /** Add one observation. */
    fun record(signal: Signal, value: Float, nowMs: Long = System.currentTimeMillis()) {
        val dow = dayOfWeek(nowMs)
        val n = prefs.getInt(k(signal, dow, "n"), 0)
        val mean = prefs.getFloat(k(signal, dow, "mean"), 0f)
        val newN = n + 1
        val newMean = mean + (value - mean) / newN
        prefs.edit()
            .putInt(k(signal, dow, "n"), newN)
            .putFloat(k(signal, dow, "mean"), newMean)
            .apply()
    }

    /**
     * Returns the most striking weekly pattern, or null if there isn't enough
     * data or no DOW deviates noticeably from the average of the others.
     */
    fun strongestPattern(minSamplesPerDow: Int = 3, minDelta: Float = 0.20f): Pattern? {
        val candidates = mutableListOf<Pattern>()
        for (signal in Signal.values()) {
            val means = (Calendar.SUNDAY..Calendar.SATURDAY).map { dow ->
                val n = prefs.getInt(k(signal, dow, "n"), 0)
                val m = prefs.getFloat(k(signal, dow, "mean"), 0f)
                if (n >= minSamplesPerDow) m else null
            }
            if (means.count { it != null } < 4) continue
            // For each DOW with enough samples, compare to the average of the
            // other DOWs that also have enough samples.
            for (i in means.indices) {
                val v = means[i] ?: continue
                val others = means.filterIndexed { j, x -> j != i && x != null }
                    .filterNotNull()
                if (others.isEmpty()) continue
                val otherMean = others.average().toFloat()
                if (otherMean == 0f) continue
                val relDelta = (v - otherMean) / otherMean
                if (kotlin.math.abs(relDelta) < minDelta) continue
                candidates.add(
                    Pattern(
                        signal = signal,
                        dayOfWeek = i + Calendar.SUNDAY,
                        dowMean = v,
                        otherMean = otherMean,
                        direction = if (relDelta < 0) Direction.LOW else Direction.HIGH,
                    )
                )
            }
        }
        return candidates.maxByOrNull {
            kotlin.math.abs((it.dowMean - it.otherMean) / it.otherMean)
        }
    }

    fun clear() = prefs.edit().clear().apply()

    /**
     * Personal baseline mean for a signal across all days-of-week that have
     * at least [minSamplesPerDow] observations. Returns null if we don't yet
     * have enough data to know what's "normal" for this user.
     */
    fun baselineMean(signal: Signal, minSamplesPerDow: Int = 3): Float? {
        val means = (Calendar.SUNDAY..Calendar.SATURDAY).mapNotNull { dow ->
            val n = prefs.getInt(k(signal, dow, "n"), 0)
            if (n >= minSamplesPerDow) prefs.getFloat(k(signal, dow, "mean"), 0f) else null
        }
        if (means.size < 4) return null
        return means.average().toFloat()
    }

    private fun dayOfWeek(ms: Long) =
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)

    private fun k(s: Signal, dow: Int, field: String) = "${s.key}.$dow.$field"
}


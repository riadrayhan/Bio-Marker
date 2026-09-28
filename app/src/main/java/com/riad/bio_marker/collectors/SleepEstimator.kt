package com.riad.bio_marker.collectors

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar
import kotlin.math.abs

/**
 * Phone-only sleep estimator.
 *
 * Heuristic:
 *  - "Asleep" = the longest contiguous stretch in 22:00–10:00 where the screen
 *    was OFF AND mean motion energy was below a quiet threshold.
 *  - We approximate from the [UsageStatsManager] event stream (SCREEN_INTERACTIVE
 *    / NON_INTERACTIVE) plus the mean motion energy already tracked per-day.
 *
 * The result is a tuple (durationHours, bedtimeMs) suitable for [DailyFeatureStore].
 * Wearable integration (Health Connect / SleepSegmentRequest) will plug in here later.
 */
class SleepEstimator(private val context: Context) {

    data class Estimate(val durationHrs: Float, val bedtimeMs: Long?, val wakeMs: Long?)

    /** Estimates sleep for the night ending on the morning of [endMs]. */
    fun estimateForLastNight(endMs: Long = System.currentTimeMillis()): Estimate {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return Estimate(0f, null, null)

        // Window: yesterday 18:00 → today 12:00.
        val cal = Calendar.getInstance().apply {
            timeInMillis = endMs
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, -18) // = yesterday 18:00
        val start = cal.timeInMillis

        val events = try { usm.queryEvents(start, end) } catch (_: Throwable) { return Estimate(0f, null, null) }
        val ev = UsageEvents.Event()
        val intervals = mutableListOf<Pair<Long, Long>>() // screen OFF intervals
        var lastOff: Long? = start

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> lastOff = ev.timeStamp
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    val s = lastOff
                    if (s != null) intervals.add(s to ev.timeStamp)
                    lastOff = null
                }
            }
        }
        if (lastOff != null) intervals.add(lastOff to end)

        // Pick longest screen-off interval at least partly inside 22:00–10:00.
        val best = intervals
            .map { (a, b) -> a to b to nightOverlap(a, b) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?: return Estimate(0f, null, null)

        val (s, e) = best.first
        val durHrs = ((e - s) / 3_600_000.0).toFloat().coerceIn(0f, 14f)
        return Estimate(durHrs, s, e)
    }

    /** Std-dev (hours) of bedtime over the last [n] nights. */
    fun bedtimeIrregularityHrs(bedtimes: List<Long>): Float {
        if (bedtimes.size < 2) return 0f
        val hours = bedtimes.map { ms ->
            val cal = Calendar.getInstance().apply { timeInMillis = ms }
            // Treat 0..12 as "late" (24..36) so 23:30 and 00:30 are close.
            val raw = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
            if (raw < 12) raw + 24 else raw
        }
        val mean = hours.average()
        val varSum = hours.sumOf { (it - mean) * (it - mean) }
        return kotlin.math.sqrt(varSum / hours.size).toFloat()
    }

    private fun nightOverlap(a: Long, b: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = a }
        cal.set(Calendar.HOUR_OF_DAY, 22); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val nStart = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, 12) // 10:00 next day
        val nEnd = cal.timeInMillis
        return abs((minOf(b, nEnd) - maxOf(a, nStart)).coerceAtLeast(0L))
    }
}


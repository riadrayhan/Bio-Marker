package com.riad.bio_marker.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Stores one wellness score per day (0..100) for the last 7 days, used to draw
 * the 7-day trend bar chart on the dashboard.
 *
 * - `record(score)` overwrites the score for *today*.
 * - `lastSeven()` returns oldest-first list of 7 entries; missing days = null.
 */
class DailyTrendStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_trend", Context.MODE_PRIVATE)

    data class DayScore(val dayKey: String, val score: Int?)

    fun record(score: Int) {
        prefs.edit().putInt(todayKey(), score.coerceIn(0, 100)).apply()
    }

    /** Oldest first: 6 days ago … today. Days with no record return null. */
    fun lastSeven(): List<DayScore> {
        val cal = Calendar.getInstance()
        val out = mutableListOf<DayScore>()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        repeat(7) {
            val key = keyOf(cal)
            val score = if (prefs.contains(key)) prefs.getInt(key, 0) else null
            out.add(DayScore(key, score))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    private fun todayKey(): String = keyOf(Calendar.getInstance())

    private fun keyOf(cal: Calendar): String =
        FORMAT.format(cal.time)

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private val FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}


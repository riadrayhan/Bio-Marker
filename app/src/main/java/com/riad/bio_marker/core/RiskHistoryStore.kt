package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray

/**
 * Persists the hourly risk-score timeline (last 30 days) for [com.riad.bio_marker.monitoring.CrisisRules].
 * Stored as a single JSON array — small enough that it doesn't justify Room yet.
 */
class RiskHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_risk_history", Context.MODE_PRIVATE)

    data class Entry(val timestampMs: Long, val score: Float)

    fun append(score: Float, nowMs: Long = System.currentTimeMillis()) {
        val arr = loadArray()
        arr.put(org.json.JSONObject().apply {
            put("t", nowMs); put("s", score.toDouble())
        })
        // Drop entries older than 30 days.
        val cutoff = nowMs - 30L * 24 * 60 * 60 * 1000
        val trimmed = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("t") >= cutoff) trimmed.put(o)
        }
        prefs.edit().putString(KEY, trimmed.toString()).apply()
    }

    fun all(): List<Entry> {
        val arr = loadArray()
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Entry(o.optLong("t"), o.optDouble("s").toFloat()))
        }
        return out
    }

    /** Daily peak risk for the last [days] days, most recent first. */
    fun dailyPeaks(days: Int = 7): List<Float> {
        val byDay = sortedMapOf<String, Float>()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        for (e in all()) {
            val k = fmt.format(java.util.Date(e.timestampMs))
            byDay[k] = maxOf(byDay[k] ?: 0f, e.score)
        }
        return byDay.values.toList().asReversed().take(days)
    }

    fun clear() = prefs.edit().clear().apply()

    private fun loadArray(): JSONArray =
        try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    companion object { private const val KEY = "history" }
}


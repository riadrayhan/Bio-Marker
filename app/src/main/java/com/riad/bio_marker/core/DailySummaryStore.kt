package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Per-day summary produced by [com.riad.bio_marker.worker.DailySummaryWorker]
 * by comparing user goals to actuals collected during the day.
 *
 * Stored as one JSON file per day under filesDir/daily_summary/summary-YYYY-MM-DD.json
 * (mirrors [DailyFeatureStore] for consistency).
 */
class DailySummaryStore(context: Context) {

    private val dir = java.io.File(context.filesDir, "daily_summary").apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    enum class Zone { LESS, NORMAL, MORE }
    enum class OverallStatus { GOOD, MIXED, ATTENTION }

    data class Summary(
        val date: String,
        val sleepActualHrs: Float,
        val walkingActualMin: Int,
        val phoneUsageActualHrs: Float,
        val talkingActualMin: Int,
        val sleepZone: Zone,
        val walkZone: Zone,
        val phoneZone: Zone,
        val talkZone: Zone,
        val overall: OverallStatus,
        val messages: List<String>,
        val createdAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("date", date)
            put("sleep_actual_hrs", sleepActualHrs.toDouble())
            put("walking_actual_min", walkingActualMin)
            put("phone_usage_actual_hrs", phoneUsageActualHrs.toDouble())
            put("talking_actual_min", talkingActualMin)
            put("sleep_zone", sleepZone.name)
            put("walk_zone", walkZone.name)
            put("phone_zone", phoneZone.name)
            put("talk_zone", talkZone.name)
            put("overall", overall.name)
            put("messages", JSONArray(messages))
            put("created_at", createdAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Summary {
                val arr = o.optJSONArray("messages")
                val msgs = mutableListOf<String>()
                if (arr != null) for (i in 0 until arr.length()) msgs.add(arr.optString(i))
                return Summary(
                    date = o.optString("date"),
                    sleepActualHrs = o.optDouble("sleep_actual_hrs").toFloat(),
                    walkingActualMin = o.optInt("walking_actual_min"),
                    phoneUsageActualHrs = o.optDouble("phone_usage_actual_hrs").toFloat(),
                    talkingActualMin = o.optInt("talking_actual_min"),
                    sleepZone = runCatching { Zone.valueOf(o.optString("sleep_zone")) }.getOrDefault(Zone.NORMAL),
                    walkZone = runCatching { Zone.valueOf(o.optString("walk_zone")) }.getOrDefault(Zone.NORMAL),
                    phoneZone = runCatching { Zone.valueOf(o.optString("phone_zone")) }.getOrDefault(Zone.NORMAL),
                    talkZone = runCatching { Zone.valueOf(o.optString("talk_zone")) }.getOrDefault(Zone.NORMAL),
                    overall = runCatching { OverallStatus.valueOf(o.optString("overall")) }
                        .getOrDefault(OverallStatus.MIXED),
                    messages = msgs,
                    createdAt = o.optLong("created_at"),
                )
            }
        }
    }

    fun save(s: Summary) {
        java.io.File(dir, "summary-${s.date}.json").writeText(s.toJson().toString())
    }

    fun load(date: String): Summary? {
        val f = java.io.File(dir, "summary-$date.json")
        if (!f.exists()) return null
        return try { Summary.fromJson(JSONObject(f.readText())) } catch (_: Exception) { null }
    }

    /** Most-recent N days (oldest first). Missing days are skipped. */
    fun lastN(n: Int): List<Summary> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
        val out = mutableListOf<Summary>()
        repeat(n) {
            load(fmt.format(cal.time))?.let(out::add)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    fun todayKey(): String = fmt.format(Date())

    fun yesterdayKey(): String {
        val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_YEAR, -1)
        return fmt.format(cal.time)
    }

    fun pruneOld(keepDays: Int = 60) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}


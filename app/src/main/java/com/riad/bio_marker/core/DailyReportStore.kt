package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Narrative end-of-window report: per-event health notes + an overall set of
 * recommendations (sleep / rest / music / social-media cap).
 *
 * Produced by [com.riad.bio_marker.rules.RecommendationEngine] from the
 * combined [DailyEventStore] + [DailySummaryStore] data and rendered by the
 * Timeline screen + appended to the nightly summary notification.
 */
class DailyReportStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "daily_report")
        .apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    data class EventNote(
        val eventId: String,
        val eventType: String,
        val note: String,
        val severity: Severity,
    ) {
        enum class Severity { POSITIVE, INFO, ATTENTION }
        fun toJson(): JSONObject = JSONObject()
            .put("eventId", eventId)
            .put("eventType", eventType)
            .put("note", note)
            .put("severity", severity.name)

        companion object {
            fun fromJson(o: JSONObject) = EventNote(
                eventId = o.optString("eventId"),
                eventType = o.optString("eventType"),
                note = o.optString("note"),
                severity = runCatching { Severity.valueOf(o.optString("severity")) }
                    .getOrDefault(Severity.INFO),
            )
        }
    }

    data class Report(
        val date: String,
        val headline: String,
        val perEventNotes: List<EventNote>,
        val recommendations: List<String>,
        val createdAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("date", date)
            put("headline", headline)
            put("perEventNotes", JSONArray().also { a -> perEventNotes.forEach { a.put(it.toJson()) } })
            put("recommendations", JSONArray(recommendations))
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Report {
                val notes = mutableListOf<EventNote>()
                val a = o.optJSONArray("perEventNotes")
                if (a != null) for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let { notes.add(EventNote.fromJson(it)) }
                }
                val recs = mutableListOf<String>()
                val r = o.optJSONArray("recommendations")
                if (r != null) for (i in 0 until r.length()) recs.add(r.optString(i))
                return Report(
                    date = o.optString("date"),
                    headline = o.optString("headline"),
                    perEventNotes = notes,
                    recommendations = recs,
                    createdAt = o.optLong("createdAt"),
                )
            }
        }
    }

    fun todayKey(): String = fmt.format(Date())

    fun save(r: Report) {
        File(dir, "report-${r.date}.json").writeText(r.toJson().toString())
    }

    fun load(date: String): Report? {
        val f = File(dir, "report-$date.json")
        if (!f.exists()) return null
        return runCatching { Report.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun pruneOld(keepDays: Int = 60) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}


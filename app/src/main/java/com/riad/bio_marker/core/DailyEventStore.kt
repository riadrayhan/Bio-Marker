package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Per-day append-only timeline of [DailyEvent]s.
 *
 * Stored as one JSON file per day at filesDir/daily_events/events-YYYY-MM-DD.json
 * (mirrors the [DailySummaryStore] / [DailyFeatureStore] layout).
 *
 * The [append] / [updateMood] / [load] entry-points are synchronised so live
 * collectors and the nightly aggregator can hit the same day-file safely.
 */
class DailyEventStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "daily_events")
        .apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun todayKey(): String = fmt.format(Date())

    fun yesterdayKey(): String {
        val cal = Calendar.getInstance(); cal.add(Calendar.DAY_OF_YEAR, -1)
        return fmt.format(cal.time)
    }

    fun keyFor(timestampMs: Long): String = fmt.format(Date(timestampMs))

    private fun fileFor(date: String) = File(dir, "events-$date.json")

    /** Generates a unique id and appends the event to the day-file matching its start time. */
    @Synchronized
    fun append(event: DailyEvent): DailyEvent {
        val withId = if (event.id.isBlank())
            event.copy(id = UUID.randomUUID().toString()) else event
        val key = keyFor(withId.startMs)
        val arr = readArray(key)
        arr.put(withId.toJson())
        fileFor(key).writeText(arr.toString())
        return withId
    }

    /** Replaces the moodLabel/moodConfidence on an existing event by id. */
    @Synchronized
    fun updateMood(date: String, eventId: String, moodLabel: String, confidence: Float) {
        val arr = readArray(date)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == eventId) {
                o.put("moodLabel", moodLabel)
                o.put("moodConfidence", confidence.toDouble())
                arr.put(i, o)
            }
        }
        fileFor(date).writeText(arr.toString())
    }

    @Synchronized
    fun load(date: String): List<DailyEvent> {
        val arr = readArray(date)
        val out = ArrayList<DailyEvent>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(DailyEvent.fromJson(it)) }
        }
        return out.sortedBy { it.startMs }
    }

    @Synchronized
    fun saveAll(date: String, events: List<DailyEvent>) {
        val arr = JSONArray()
        events.forEach { arr.put(it.toJson()) }
        fileFor(date).writeText(arr.toString())
    }

    private fun readArray(date: String): JSONArray {
        val f = fileFor(date)
        if (!f.exists()) return JSONArray()
        return runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
    }

    fun pruneOld(keepDays: Int = 60) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}

/**
 * Convenience helper for collectors: build a [DailyEvent], persist it via
 * [ServiceLocator.dailyEventStore] and return the stored copy (with id).
 * Returns `null` if the corresponding consent signal is disabled.
 */
internal fun ServiceLocator.recordEvent(
    type: DailyEvent.Type,
    startMs: Long,
    endMs: Long,
    source: String,
    metadata: Map<String, String> = emptyMap(),
    requiredSignal: ConsentStore.Signal? = null,
): DailyEvent? {
    if (requiredSignal != null && !consentStore.isEnabled(requiredSignal)) return null
    if (endMs <= startMs) return null
    val ev = DailyEvent(
        id = "",
        type = type,
        startMs = startMs,
        endMs = endMs,
        source = source,
        metadata = metadata,
    )
    return dailyEventStore.append(ev)
}


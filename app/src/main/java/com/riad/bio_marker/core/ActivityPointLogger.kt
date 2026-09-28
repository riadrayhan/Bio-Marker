package com.riad.bio_marker.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight per-event "what the user did" timeline.
 *
 * Each call to [log] appends one JSON line to today's file at
 * <filesDir>/activity_points/points-YYYY-MM-DD.jsonl. Reads are range-scoped
 * by [pointsBetween] so a monitoring window can pull *exactly* its own
 * action timeline for the Groq AI prompt.
 *
 * Privacy: only short non-content labels are stored — never raw text,
 * audio waveforms, or message bodies. Examples:
 *   - "service.start" / "service.stop"
 *   - "checkin.voice" / "checkin.face"
 *   - "screen.on" / "screen.off" / "screen.unlock"
 *   - "walk.start" / "walk.end (1240 steps)"
 *   - "call.start" / "call.end (3m)"
 *   - "voice.utterance (12 words)"
 *   - "app.foreground (com.whatsapp)"
 */
class ActivityPointLogger(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "activity_points")
        .apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun fileFor(timeMs: Long): File =
        File(dir, "points-${fmt.format(Date(timeMs))}.jsonl")

    @Synchronized
    fun log(kind: String, label: String, meta: Map<String, String> = emptyMap()) {
        val now = System.currentTimeMillis()
        val o = JSONObject().apply {
            put("ts", now)
            put("k", kind)
            put("l", label.take(80))
            if (meta.isNotEmpty()) put("m", JSONObject(meta as Map<*, *>))
        }
        try {
            FileWriter(fileFor(now), /* append = */ true).use {
                it.appendLine(o.toString())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "log() failed: ${t.message}")
        }
    }

    data class Point(
        val tsMs: Long,
        val kind: String,
        val label: String,
        val meta: Map<String, String>,
    )

    /** Returns chronologically ordered points whose ts is in [startMs, endMs]. */
    @Synchronized
    fun pointsBetween(startMs: Long, endMs: Long): List<Point> {
        if (endMs <= startMs) return emptyList()
        val out = ArrayList<Point>()
        // Walk through each calendar day touched by the range — windows are
        // ≤ 72 h so at most 4 day-files are read.
        val oneDay = 24L * 60 * 60 * 1000
        var t = startMs
        while (t <= endMs + oneDay) {
            val f = fileFor(t)
            if (f.exists()) {
                f.forEachLine { line ->
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    val ts = o.optLong("ts")
                    if (ts in startMs..endMs) {
                        val mdJ = o.optJSONObject("m")
                        val md = if (mdJ == null) emptyMap() else {
                            val m = HashMap<String, String>()
                            val it = mdJ.keys()
                            while (it.hasNext()) {
                                val k = it.next()
                                m[k] = mdJ.optString(k, "")
                            }
                            m
                        }
                        out += Point(ts, o.optString("k"), o.optString("l"), md)
                    }
                }
            }
            if (t > endMs) break
            t += oneDay
        }
        return out.sortedBy { it.tsMs }
    }

    /** Returns at most [maxLabels] short label strings for the Groq prompt. */
    fun topLabels(points: List<Point>, maxLabels: Int = 30): List<String> =
        points.takeLast(maxLabels).map { p ->
            // hh:mm + label so the model can see ordering inside the window
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = p.tsMs }
            "%02d:%02d %s".format(
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                p.label,
            )
        }

    fun pruneOld(keepDays: Int = 14) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** For the dashboard — last N points across all files. */
    @Synchronized
    fun recent(n: Int = 50): List<Point> {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val out = ArrayList<Point>()
        outer@ for (f in files) {
            f.forEachLine { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val mdJ = o.optJSONObject("m")
                val md = if (mdJ == null) emptyMap() else {
                    val m = HashMap<String, String>()
                    val it = mdJ.keys()
                    while (it.hasNext()) {
                        val k = it.next(); m[k] = mdJ.optString(k, "")
                    }
                    m
                }
                out += Point(o.optLong("ts"), o.optString("k"), o.optString("l"), md)
            }
            if (out.size >= n) break@outer
        }
        return out.sortedByDescending { it.tsMs }.take(n)
    }

    companion object {
        private const val TAG = "ActivityPointLogger"
    }
}


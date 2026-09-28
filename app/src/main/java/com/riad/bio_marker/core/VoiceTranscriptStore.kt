package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-window log of every utterance (English + Bengali) the user spoke
 * during a monitoring session, captured by
 * [com.riad.bio_marker.collectors.PassiveVoiceCollector] using on-device
 * speech recognition.
 *
 * Each entry stores:
 *   - `windowStartMs` — which monitoring window this utterance belongs to
 *     (so multiple sessions can co-exist on disk without confusing the
 *     end-of-window AI summary).
 *   - `timestampMs`   — wall-clock time the utterance was recognised.
 *   - `text`          — raw transcript text (kept locally so the user can
 *                       review and so the end-of-window Groq prompt can do
 *                       a fine-grained, per-period mood breakdown).
 *   - `lang`          — BCP-47 tag the recogniser was using.
 *   - `wordCount` / `sentimentScore` / `mood` — derived counters from
 *                       [com.riad.bio_marker.collectors.TextAnalyzer], so the
 *                       UI can colour-code each entry without re-analysing.
 *
 * Privacy: the transcript text never leaves the device unless the user
 * explicitly taps "Analyse with AI" in the Voice notes screen, in which
 * case it's POSTed to the Groq endpoint (same path as the rest of the
 * window summary). The user can clear the log at any time.
 *
 * The Groq verdict for the latest finalised window is cached separately in
 * SharedPreferences so the Voice notes screen can show "AI mood timeline"
 * even after the user reopens the app days later.
 */
class VoiceTranscriptStore(context: Context) {

    private val ctx = context.applicationContext

    private val file: File = File(ctx.filesDir, "voice_transcripts.json")

    private val verdictPrefs = ctx.getSharedPreferences(
        "biomarker_voice_transcript_verdict", Context.MODE_PRIVATE
    )

    data class Entry(
        val windowStartMs: Long,
        val timestampMs: Long,
        val text: String,
        val lang: String,
        val wordCount: Int,
        val sentimentScore: Float,
        /** "positive" / "neutral" / "negative". */
        val mood: String,
        /** Where the text came from: "voice" (spoken, transcribed by the
         *  on-device recogniser) or "keyboard" (typed on the in-app IME).
         *  Both feed the same end-of-window Groq decision. */
        val source: String = "voice",
    )

    data class AiVerdict(
        val windowStartMs: Long,
        val windowEndMs: Long,
        val createdAtMs: Long,
        val headline: String,
        val analysis: String,
        val recommendations: List<String>,
        val engine: String,
    )

    @Synchronized
    fun append(entry: Entry) {
        val arr = readArray()
        arr.put(entryToJson(entry))
        val trimmed = if (arr.length() > MAX_ENTRIES) {
            val start = arr.length() - MAX_ENTRIES
            JSONArray().also { out ->
                for (i in start until arr.length()) out.put(arr.getJSONObject(i))
            }
        } else arr
        file.writeText(trimmed.toString())
    }

    @Synchronized
    fun entriesFor(windowStartMs: Long): List<Entry> {
        val arr = readArray()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("windowStartMs") == windowStartMs) out.add(jsonToEntry(o))
        }
        return out.sortedBy { it.timestampMs }
    }

    /** All entries across every window, newest first. Used by the Voice
     *  notes screen when there is no active monitoring session. */
    @Synchronized
    fun allEntries(): List<Entry> {
        val arr = readArray()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(jsonToEntry(o))
        }
        return out.sortedByDescending { it.timestampMs }
    }

    @Synchronized
    fun clearWindow(windowStartMs: Long) {
        val arr = readArray()
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("windowStartMs") != windowStartMs) keep.put(o)
        }
        file.writeText(keep.toString())
    }

    @Synchronized
    fun clearAll() {
        if (file.exists()) file.writeText("[]")
        verdictPrefs.edit().clear().apply()
    }

    fun saveVerdict(v: AiVerdict) {
        val recs = JSONArray()
        for (r in v.recommendations) recs.put(r)
        verdictPrefs.edit()
            .putLong(K_WIN_START, v.windowStartMs)
            .putLong(K_WIN_END, v.windowEndMs)
            .putLong(K_CREATED, v.createdAtMs)
            .putString(K_HEADLINE, v.headline)
            .putString(K_ANALYSIS, v.analysis)
            .putString(K_RECS, recs.toString())
            .putString(K_ENGINE, v.engine)
            .apply()
    }

    fun loadVerdict(): AiVerdict? {
        if (!verdictPrefs.contains(K_WIN_START)) return null
        val recsRaw = verdictPrefs.getString(K_RECS, "[]") ?: "[]"
        val recs = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(recsRaw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).orEmpty().trim()
                if (s.isNotEmpty()) recs.add(s)
            }
        }
        return AiVerdict(
            windowStartMs = verdictPrefs.getLong(K_WIN_START, 0L),
            windowEndMs = verdictPrefs.getLong(K_WIN_END, 0L),
            createdAtMs = verdictPrefs.getLong(K_CREATED, 0L),
            headline = verdictPrefs.getString(K_HEADLINE, "") ?: "",
            analysis = verdictPrefs.getString(K_ANALYSIS, "") ?: "",
            recommendations = recs,
            engine = verdictPrefs.getString(K_ENGINE, "") ?: "",
        )
    }

    /**
     * Returns the latest verdict only if the user hasn't already seen /
     * dismissed it. Used to auto-surface the "how was your mood" screen the
     * next time the app is opened if the window-end notification was missed.
     */
    fun unacknowledgedVerdict(): AiVerdict? {
        val v = loadVerdict() ?: return null
        if (v.headline.isBlank() && v.analysis.isBlank()) return null
        val ackWin = verdictPrefs.getLong(K_ACK_WIN, -1L)
        return if (ackWin == v.windowStartMs) null else v
    }

    /** Mark the current verdict as seen so it isn't auto-shown again. */
    fun acknowledgeVerdict() {
        val win = verdictPrefs.getLong(K_WIN_START, 0L)
        verdictPrefs.edit().putLong(K_ACK_WIN, win).apply()
    }

    private fun readArray(): JSONArray {
        if (!file.exists()) return JSONArray()
        return runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
    }

    private fun entryToJson(e: Entry): JSONObject = JSONObject()
        .put("windowStartMs", e.windowStartMs)
        .put("timestampMs", e.timestampMs)
        .put("text", e.text)
        .put("lang", e.lang)
        .put("wordCount", e.wordCount)
        .put("sentimentScore", e.sentimentScore.toDouble())
        .put("mood", e.mood)
        .put("source", e.source)

    private fun jsonToEntry(o: JSONObject) = Entry(
        windowStartMs = o.optLong("windowStartMs"),
        timestampMs = o.optLong("timestampMs"),
        text = o.optString("text", ""),
        lang = o.optString("lang", ""),
        wordCount = o.optInt("wordCount", 0),
        sentimentScore = o.optDouble("sentimentScore", 0.0).toFloat(),
        mood = o.optString("mood", "neutral"),
        source = o.optString("source", "voice"),
    )

    companion object {
        private const val MAX_ENTRIES = 5_000
        private const val K_WIN_START = "win_start"
        private const val K_WIN_END = "win_end"
        private const val K_CREATED = "created"
        private const val K_HEADLINE = "headline"
        private const val K_ANALYSIS = "analysis"
        private const val K_RECS = "recs_json"
        private const val K_ENGINE = "engine"
        private const val K_ACK_WIN = "ack_win"
    }
}


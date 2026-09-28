package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-window mood-sample timeline. Originally driven by a face-detection
 * collector that has since been removed from the app, this store is kept
 * only so historical samples persisted on-disk can still be read and rolled
 * into a final verdict by [com.riad.bio_marker.worker.MoodWindowEndWorker].
 *
 * Going forward no new samples are written here; the mood verdict comes
 * from the voice-transcript pipeline via [com.riad.bio_marker.ml.GroqAgentClient].
 */
class MoodTimelineStore(context: Context) {

    private val file: File = File(
        context.applicationContext.filesDir,
        "mood_timeline.json"
    )

    data class MoodSample(
        val windowStartMs: Long,
        val timestampMs: Long,
        val smileProb: Float,       // 0..1
        val eyesOpenProb: Float,    // 0..1
        val facePresent: Float,     // 0..1
        val headMotion: Float,      // degrees std-dev
        val framesAnalysed: Int,
        val label: String,          // MoodSummarizer.Label.name
    )

    @Synchronized
    fun append(sample: MoodSample) {
        val arr = readArray()
        arr.put(sampleToJson(sample))
        // Keep at most ~10k samples to bound disk usage (≈1 MB).
        val trimmed = if (arr.length() > MAX_SAMPLES) {
            val start = arr.length() - MAX_SAMPLES
            JSONArray().also { out ->
                for (i in start until arr.length()) out.put(arr.getJSONObject(i))
            }
        } else arr
        file.writeText(trimmed.toString())
    }

    @Synchronized
    fun samplesFor(windowStartMs: Long): List<MoodSample> {
        val arr = readArray()
        val out = ArrayList<MoodSample>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("windowStartMs") == windowStartMs) out.add(jsonToSample(o))
        }
        return out
    }


    @Synchronized
    fun samplesBetween(startMs: Long, endMs: Long): List<MoodSample> {
        val arr = readArray()
        val out = ArrayList<MoodSample>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("timestampMs")
            if (ts in startMs..endMs) out.add(jsonToSample(o))
        }
        return out
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

    private fun readArray(): JSONArray {
        if (!file.exists()) return JSONArray()
        return runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
    }

    private fun sampleToJson(s: MoodSample): JSONObject = JSONObject()
        .put("windowStartMs", s.windowStartMs)
        .put("timestampMs", s.timestampMs)
        .put("smileProb", s.smileProb.toDouble())
        .put("eyesOpenProb", s.eyesOpenProb.toDouble())
        .put("facePresent", s.facePresent.toDouble())
        .put("headMotion", s.headMotion.toDouble())
        .put("framesAnalysed", s.framesAnalysed)
        .put("label", s.label)

    private fun jsonToSample(o: JSONObject) = MoodSample(
        windowStartMs = o.optLong("windowStartMs"),
        timestampMs = o.optLong("timestampMs"),
        smileProb = o.optDouble("smileProb", 0.0).toFloat(),
        eyesOpenProb = o.optDouble("eyesOpenProb", 0.0).toFloat(),
        facePresent = o.optDouble("facePresent", 0.0).toFloat(),
        headMotion = o.optDouble("headMotion", 0.0).toFloat(),
        framesAnalysed = o.optInt("framesAnalysed", 0),
        label = o.optString("label", "NEUTRAL"),
    )

    companion object {
        private const val MAX_SAMPLES = 10_000
    }
}

/**
 * Helpers for turning a timestamp-ordered list of [MoodTimelineStore.MoodSample]
 * into per-mood durations + transition counts. Used by [MoodWindowEndWorker]
 * + [MoodSummaryActivity] to render "time spent happy / stressed / …".
 *
 * The gap between two consecutive samples is attributed to the *earlier*
 * sample's label (the user was in that mood until the next sample changed
 * it), capped at [MAX_GAP_MS] so a long screen-off / camera-blocked gap
 * does not inflate one mood. The final sample contributes [BUCKET_INTERVAL_MS]
 * if it is recent enough.
 */
object MoodTimelineAnalyzer {

    /** Gap longer than this between samples is treated as monitoring-paused. */
    private const val MAX_GAP_MS = 60_000L
    private const val BUCKET_INTERVAL_MS = 30_000L

    /** Returns total milliseconds spent in each label. NO_FACE excluded. */
    fun breakdown(samples: List<MoodTimelineStore.MoodSample>): Map<String, Long> {
        if (samples.isEmpty()) return emptyMap()
        val sorted = samples.sortedBy { it.timestampMs }
        val out = HashMap<String, Long>()
        for (i in sorted.indices) {
            val s = sorted[i]
            if (s.label == "NO_FACE") continue
            val nextTs = sorted.getOrNull(i + 1)?.timestampMs
            val gap = if (nextTs != null) {
                (nextTs - s.timestampMs).coerceAtMost(MAX_GAP_MS)
            } else {
                BUCKET_INTERVAL_MS
            }
            if (gap > 0) out[s.label] = (out[s.label] ?: 0L) + gap
        }
        return out
    }

    /** Counts label changes (e.g. HAPPY→STRESSED is one transition). */
    fun transitions(samples: List<MoodTimelineStore.MoodSample>): Int {
        if (samples.size < 2) return 0
        val sorted = samples.sortedBy { it.timestampMs }
        var count = 0
        var prev: String? = null
        for (s in sorted) {
            if (s.label == "NO_FACE") continue
            if (prev != null && prev != s.label) count++
            prev = s.label
        }
        return count
    }
}


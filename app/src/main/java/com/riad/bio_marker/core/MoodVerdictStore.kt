package com.riad.bio_marker.core

import android.content.Context
import com.riad.bio_marker.ml.MoodSummarizer
import org.json.JSONObject

/**
 * Persists the most recently *finalised* mood verdict for an expired
 * monitoring window. Read by [com.riad.bio_marker.ui.MoodSummaryActivity]
 * and used by [com.riad.bio_marker.ui.ToolsFragment] to detect that there
 * is a new, unseen verdict to surface.
 */
class MoodVerdictStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_mood_verdict", Context.MODE_PRIVATE)

    data class Frozen(
        val windowStartMs: Long,
        val windowEndMs: Long,
        val dominantLabel: MoodSummarizer.Label,
        val avgSmile: Float,
        val avgEyesOpen: Float,
        val avgHeadMotion: Float,
        val sampleCount: Int,
        val negativeShare: Float,
        val acknowledged: Boolean,
        /** Per-label durations in ms (label-name → ms). May be empty on legacy data. */
        val durationsMs: Map<String, Long> = emptyMap(),
        /** How many times the user's mood label changed in this window. */
        val transitions: Int = 0,
        /** Average heart rate (bpm) over the window, if a HR sensor was active. */
        val heartRateAvg: Float? = null,
    )

    fun save(
        verdict: MoodSummarizer.Verdict,
        windowStartMs: Long,
        windowEndMs: Long,
        durationsMs: Map<String, Long> = emptyMap(),
        transitions: Int = 0,
        heartRateAvg: Float? = null,
    ) {
        val durJson = JSONObject().apply {
            for ((k, v) in durationsMs) put(k, v)
        }.toString()
        prefs.edit()
            .putLong(K_WIN_START, windowStartMs)
            .putLong(K_WIN_END, windowEndMs)
            .putString(K_LABEL, verdict.dominant.name)
            .putFloat(K_SMILE, verdict.avgSmile)
            .putFloat(K_EYES, verdict.avgEyesOpen)
            .putFloat(K_MOTION, verdict.avgHeadMotion)
            .putInt(K_COUNT, verdict.sampleCount)
            .putFloat(K_NEG, verdict.negativeShare)
            .putBoolean(K_ACK, false)
            .putString(K_DURATIONS, durJson)
            .putInt(K_TRANSITIONS, transitions)
            .putFloat(K_HR_AVG, heartRateAvg ?: -1f)
            .apply()
    }

    fun load(): Frozen? {
        if (!prefs.contains(K_WIN_START)) return null
        val labelName = prefs.getString(K_LABEL, MoodSummarizer.Label.NEUTRAL.name)!!
        val label = runCatching { MoodSummarizer.Label.valueOf(labelName) }
            .getOrDefault(MoodSummarizer.Label.NEUTRAL)
        // Parse persisted durations JSON (legacy entries → empty map).
        val durMap = HashMap<String, Long>()
        runCatching {
            val raw = prefs.getString(K_DURATIONS, null) ?: return@runCatching
            val o = JSONObject(raw)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                durMap[k] = o.optLong(k, 0L)
            }
        }
        val hr = prefs.getFloat(K_HR_AVG, -1f).let { if (it < 0f) null else it }
        return Frozen(
            windowStartMs = prefs.getLong(K_WIN_START, 0L),
            windowEndMs = prefs.getLong(K_WIN_END, 0L),
            dominantLabel = label,
            avgSmile = prefs.getFloat(K_SMILE, 0f),
            avgEyesOpen = prefs.getFloat(K_EYES, 0f),
            avgHeadMotion = prefs.getFloat(K_MOTION, 0f),
            sampleCount = prefs.getInt(K_COUNT, 0),
            negativeShare = prefs.getFloat(K_NEG, 0f),
            acknowledged = prefs.getBoolean(K_ACK, false),
            durationsMs = durMap,
            transitions = prefs.getInt(K_TRANSITIONS, 0),
            heartRateAvg = hr,
        )
    }

    fun acknowledge() = prefs.edit().putBoolean(K_ACK, true).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val K_WIN_START = "win_start"
        private const val K_WIN_END = "win_end"
        private const val K_LABEL = "label"
        private const val K_SMILE = "smile"
        private const val K_EYES = "eyes"
        private const val K_MOTION = "motion"
        private const val K_COUNT = "count"
        private const val K_NEG = "neg"
        private const val K_ACK = "ack"
        private const val K_DURATIONS = "durations_json"
        private const val K_TRANSITIONS = "transitions"
        private const val K_HR_AVG = "hr_avg"
    }
}


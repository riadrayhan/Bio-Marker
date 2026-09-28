package com.riad.bio_marker.core

import org.json.JSONObject

/**
 * One discrete activity event detected during the day.
 *
 * Events are produced by the various per-activity collectors:
 *   - WALK     → [com.riad.bio_marker.collectors.WalkBoutDetector]
 *   - CALL     → [com.riad.bio_marker.collectors.CallSessionCollector]
 *   - APP_USE  → [com.riad.bio_marker.collectors.UsageStatsCollector] (foreground sessions)
 *   - TYPING   → [com.riad.bio_marker.collectors.TypingSessionAggregator]
 *   - MEETING  → [com.riad.bio_marker.collectors.MeetingDetector] (best-effort)
 *   - SLEEP    → [com.riad.bio_marker.collectors.SleepInferenceEngine]
 *
 * Mood is filled in later by [com.riad.bio_marker.ml.EventMoodTagger]
 * (joins face/voice/typing-sentiment timelines with the event window).
 */
data class DailyEvent(
    val id: String,
    val type: Type,
    val startMs: Long,
    val endMs: Long,
    /** Free-form source identifier (e.g. "step_detector", "telephony", "usage_stats"). */
    val source: String,
    /** Optional mood label for the event window — set by EventMoodTagger. */
    val moodLabel: String? = null,
    /** 0..1, set by EventMoodTagger. */
    val moodConfidence: Float? = null,
    /** Free-form metadata: package name, app category, num steps, calm/agitated, etc. */
    val metadata: Map<String, String> = emptyMap(),
) {
    val durationSec: Long get() = ((endMs - startMs) / 1000L).coerceAtLeast(0L)
    val durationMin: Int get() = (durationSec / 60L).toInt()

    enum class Type { WALK, CALL, APP_USE, TYPING, MEETING, SLEEP }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("startMs", startMs)
        put("endMs", endMs)
        put("source", source)
        moodLabel?.let { put("moodLabel", it) }
        moodConfidence?.let { put("moodConfidence", it.toDouble()) }
        if (metadata.isNotEmpty()) put("metadata", JSONObject(metadata as Map<*, *>))
    }

    companion object {
        fun fromJson(o: JSONObject): DailyEvent {
            val md = o.optJSONObject("metadata")
            val map = if (md != null) {
                val m = HashMap<String, String>()
                val it = md.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k] = md.optString(k, "")
                }
                m
            } else emptyMap()
            return DailyEvent(
                id = o.optString("id"),
                type = runCatching { Type.valueOf(o.optString("type")) }.getOrDefault(Type.APP_USE),
                startMs = o.optLong("startMs"),
                endMs = o.optLong("endMs"),
                source = o.optString("source"),
                moodLabel = if (o.has("moodLabel")) o.optString("moodLabel") else null,
                moodConfidence = if (o.has("moodConfidence"))
                    o.optDouble("moodConfidence").toFloat() else null,
                metadata = map,
            )
        }
    }
}


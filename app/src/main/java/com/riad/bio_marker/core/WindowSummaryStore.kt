package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Final per-monitoring-window snapshot — the single object that the
 * Groq AI agent receives and that the dashboard renders after the
 * window expires.
 *
 * Keyed by `windowStartMs` so historical windows live side-by-side.
 *
 * Stored as JSON at filesDir/window_summary/window-<startMs>.json.
 */
class WindowSummaryStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "window_summary")
        .apply { mkdirs() }

    /**
     * Everything the user asked us to track during the window:
     *   1. walking time / steps,
     *   2. location-change count,
     *   3. heart-rate samples (best-effort),
     *   4. face-derived mood verdict,
     *   5. extra health metrics (sleep, motion energy std, …),
     *   6. conversation positivity (voice sentiment + face mood),
     *   7. phone-call talk time vs phone-down time,
     *   8. total phone use vs social-media time.
     *
     * Plus the AI agent's free-form analysis text + headline once Groq
     * has been called.
     */
    data class WindowSummary(
        val windowStartMs: Long,
        val windowEndMs: Long,
        val periodHours: Int,

        // 1. Walking
        val walkingMinutes: Int,
        val walkingBouts: Int,
        val totalSteps: Int,

        // 2. Location
        val locationFixes: Int,
        val locationEntropy: Float,
        val radiusKm: Float,

        // 3. Vitals (best-effort, may be null)
        val heartRateAvg: Float?,
        val heartRateMin: Float?,
        val heartRateMax: Float?,

        // 4. Face mood
        val moodLabel: String,
        val moodNegativeShare: Float,    // 0..1, share of SAD/STRESSED/TIRED face samples
        val moodSampleCount: Int,

        // 5. Other health
        val sleepHours: Float,
        val motionAnomalies: Int,

        // 6. Conversation tone
        /** From VoiceFeatureCache.sentimentScore (-1..1). null if no voice sample in window. */
        val voiceSentiment: Float?,
        /** Total minutes user appears to have been *speaking* (calls + meetings). */
        val talkingMinutes: Int,

        // 7. Phone calls
        val callMinutes: Int,
        val callCount: Int,

        // 8. App usage
        val totalPhoneUseMinutes: Int,
        val socialMediaMinutes: Int,
        val videoMinutes: Int,
        val socialAppRatio: Float,

        // 9. Routine + vitals (added v1.1) — all optional / null-safe.
        val routineScore: Int = -1,           // 0..100, -1 if unknown
        val wakeTimeLocal: String? = null,    // "HH:mm"
        val bedTimeLocal: String? = null,     // "HH:mm"
        val routineFeedback: String? = null,
        val bpSystolic: Int? = null,
        val bpDiastolic: Int? = null,
        val actionPointCount: Int = 0,
        val topActionPoints: List<String> = emptyList(),

        // -- AI agent output (set by GroqAgentClient) --
        val aiHeadline: String? = null,
        val aiAnalysis: String? = null,
        val aiRecommendations: List<String> = emptyList(),
        val aiEngine: String? = null,
        val aiCreatedAtMs: Long? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("windowStartMs", windowStartMs)
            put("windowEndMs", windowEndMs)
            put("periodHours", periodHours)
            put("walkingMinutes", walkingMinutes)
            put("walkingBouts", walkingBouts)
            put("totalSteps", totalSteps)
            put("locationFixes", locationFixes)
            put("locationEntropy", locationEntropy.toDouble())
            put("radiusKm", radiusKm.toDouble())
            heartRateAvg?.let { put("heartRateAvg", it.toDouble()) }
            heartRateMin?.let { put("heartRateMin", it.toDouble()) }
            heartRateMax?.let { put("heartRateMax", it.toDouble()) }
            put("moodLabel", moodLabel)
            put("moodNegativeShare", moodNegativeShare.toDouble())
            put("moodSampleCount", moodSampleCount)
            put("sleepHours", sleepHours.toDouble())
            put("motionAnomalies", motionAnomalies)
            voiceSentiment?.let { put("voiceSentiment", it.toDouble()) }
            put("talkingMinutes", talkingMinutes)
            put("callMinutes", callMinutes)
            put("callCount", callCount)
            put("totalPhoneUseMinutes", totalPhoneUseMinutes)
            put("socialMediaMinutes", socialMediaMinutes)
            put("videoMinutes", videoMinutes)
            put("socialAppRatio", socialAppRatio.toDouble())
            if (routineScore >= 0) put("routineScore", routineScore)
            wakeTimeLocal?.let { put("wakeTimeLocal", it) }
            bedTimeLocal?.let { put("bedTimeLocal", it) }
            routineFeedback?.let { put("routineFeedback", it) }
            bpSystolic?.let { put("bpSystolic", it) }
            bpDiastolic?.let { put("bpDiastolic", it) }
            if (actionPointCount > 0) put("actionPointCount", actionPointCount)
            if (topActionPoints.isNotEmpty()) put("topActionPoints", JSONArray(topActionPoints))
            aiHeadline?.let { put("aiHeadline", it) }
            aiAnalysis?.let { put("aiAnalysis", it) }
            if (aiRecommendations.isNotEmpty()) put("aiRecommendations", JSONArray(aiRecommendations))
            aiEngine?.let { put("aiEngine", it) }
            aiCreatedAtMs?.let { put("aiCreatedAtMs", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): WindowSummary {
                val recs = mutableListOf<String>()
                val a = o.optJSONArray("aiRecommendations")
                if (a != null) for (i in 0 until a.length()) recs.add(a.optString(i))
                val tap = mutableListOf<String>()
                val b = o.optJSONArray("topActionPoints")
                if (b != null) for (i in 0 until b.length()) tap.add(b.optString(i))
                return WindowSummary(
                    windowStartMs = o.optLong("windowStartMs"),
                    windowEndMs = o.optLong("windowEndMs"),
                    periodHours = o.optInt("periodHours"),
                    walkingMinutes = o.optInt("walkingMinutes"),
                    walkingBouts = o.optInt("walkingBouts"),
                    totalSteps = o.optInt("totalSteps"),
                    locationFixes = o.optInt("locationFixes"),
                    locationEntropy = o.optDouble("locationEntropy", 0.0).toFloat(),
                    radiusKm = o.optDouble("radiusKm", 0.0).toFloat(),
                    heartRateAvg = if (o.has("heartRateAvg")) o.optDouble("heartRateAvg").toFloat() else null,
                    heartRateMin = if (o.has("heartRateMin")) o.optDouble("heartRateMin").toFloat() else null,
                    heartRateMax = if (o.has("heartRateMax")) o.optDouble("heartRateMax").toFloat() else null,
                    moodLabel = o.optString("moodLabel", "NO_FACE"),
                    moodNegativeShare = o.optDouble("moodNegativeShare", 0.0).toFloat(),
                    moodSampleCount = o.optInt("moodSampleCount"),
                    sleepHours = o.optDouble("sleepHours", 0.0).toFloat(),
                    motionAnomalies = o.optInt("motionAnomalies"),
                    voiceSentiment = if (o.has("voiceSentiment"))
                        o.optDouble("voiceSentiment").toFloat() else null,
                    talkingMinutes = o.optInt("talkingMinutes"),
                    callMinutes = o.optInt("callMinutes"),
                    callCount = o.optInt("callCount"),
                    totalPhoneUseMinutes = o.optInt("totalPhoneUseMinutes"),
                    socialMediaMinutes = o.optInt("socialMediaMinutes"),
                    videoMinutes = o.optInt("videoMinutes"),
                    socialAppRatio = o.optDouble("socialAppRatio", 0.0).toFloat(),
                    routineScore = if (o.has("routineScore")) o.optInt("routineScore") else -1,
                    wakeTimeLocal = o.optString("wakeTimeLocal").takeIf { it.isNotEmpty() },
                    bedTimeLocal = o.optString("bedTimeLocal").takeIf { it.isNotEmpty() },
                    routineFeedback = o.optString("routineFeedback").takeIf { it.isNotEmpty() },
                    bpSystolic = if (o.has("bpSystolic")) o.optInt("bpSystolic") else null,
                    bpDiastolic = if (o.has("bpDiastolic")) o.optInt("bpDiastolic") else null,
                    actionPointCount = o.optInt("actionPointCount"),
                    topActionPoints = tap,
                    aiHeadline = o.optString("aiHeadline").takeIf { it.isNotEmpty() },
                    aiAnalysis = o.optString("aiAnalysis").takeIf { it.isNotEmpty() },
                    aiRecommendations = recs,
                    aiEngine = o.optString("aiEngine").takeIf { it.isNotEmpty() },
                    aiCreatedAtMs = if (o.has("aiCreatedAtMs")) o.optLong("aiCreatedAtMs") else null,
                )
            }
        }
    }

    fun save(s: WindowSummary) {
        File(dir, "window-${s.windowStartMs}.json").writeText(s.toJson().toString())
    }

    fun load(windowStartMs: Long): WindowSummary? {
        val f = File(dir, "window-$windowStartMs.json")
        if (!f.exists()) return null
        return runCatching { WindowSummary.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun latest(): WindowSummary? {
        val files = dir.listFiles()?.filter { it.name.startsWith("window-") } ?: return null
        val newest = files.maxByOrNull { it.lastModified() } ?: return null
        return runCatching { WindowSummary.fromJson(JSONObject(newest.readText())) }.getOrNull()
    }

    fun pruneOld(keep: Int = 30) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size <= keep) return
        files.drop(keep).forEach { it.delete() }
    }
}


package com.riad.bio_marker.core

import android.content.Context
import com.riad.bio_marker.collectors.CallLogCollector
import com.riad.bio_marker.collectors.UsageStatsCollector
import com.riad.bio_marker.ml.MoodSummarizer

/**
 * Aggregates every signal collected during a monitoring window
 * `[windowStartMs, windowEndMs]` into a single [WindowSummaryStore.WindowSummary].
 *
 * Sources:
 *  - **Walking** : [DailyEventStore] WALK events that overlap the window.
 *  - **Steps**   : sum of WALK metadata `steps` (best-effort — falls back to
 *                  [MetricsBus.stepsToday] if no bouts captured).
 *  - **Location**: live [com.riad.bio_marker.collectors.LocationCollector]
 *                  via [ServiceLocator.activeLocationCollector].
 *  - **Heart rate**: optional [com.riad.bio_marker.collectors.HeartRateCollector]
 *                  exposed via [ServiceLocator.activeHeartRateCollector].
 *  - **Mood**    : [MoodTimelineStore] face samples → [MoodSummarizer.Verdict].
 *  - **Sleep**   : last reading from [DailyFeatureStore].
 *  - **Voice**   : [com.riad.bio_marker.ml.VoiceFeatureCache] sentimentScore.
 *  - **Calls**   : [CallLogCollector] for the window's date range.
 *  - **Usage**   : [UsageStatsCollector] (today's snapshot — close enough for
 *                  ≤24h windows; for longer windows we re-query the full range).
 *
 * The result is a pure data object suitable for both the dashboard and the
 * Groq agent prompt.
 */
object WindowSummaryBuilder {

    fun build(
        ctx: Context,
        windowStartMs: Long,
        windowEndMs: Long,
        periodHours: Int,
    ): WindowSummaryStore.WindowSummary {

        // ---- 1. Walking + steps (from DailyEventStore WALK events) ----
        val events = collectEventsInWindow(windowStartMs, windowEndMs)
        val walkEvents = events.filter { it.type == DailyEvent.Type.WALK }
        val walkingMinutes = walkEvents.sumOf { it.durationMin }
        val walkingBouts = walkEvents.size
        val stepsFromBouts = walkEvents
            .sumOf { it.metadata["steps"]?.toIntOrNull() ?: 0 }
        val totalSteps = if (stepsFromBouts > 0) stepsFromBouts
            else ServiceLocator.metricsBus.state.value.stepsToday

        // ---- 2. Location ----
        val loc = ServiceLocator.activeLocationCollector
        val locationFixes = loc?.fixCount() ?: 0
        val locationEntropy = loc?.entropy() ?: 0f
        val radiusKm = loc?.radiusKm() ?: 0f


        val hr = ServiceLocator.activeHeartRateCollector?.stats()
        val ppgHr = ServiceLocator.bloodPressureStore.latestHr()?.takeIf { r ->
            System.currentTimeMillis() - r.tsMs <= 24L * 60 * 60 * 1000
        }
        val heartRateAvg = hr?.avg ?: ppgHr?.bpm?.toFloat()
        val heartRateMin = hr?.min ?: ppgHr?.bpm?.toFloat()
        val heartRateMax = hr?.max ?: ppgHr?.bpm?.toFloat()


        // Face-based mood detection was removed; mood verdict now comes
        // exclusively from voice transcripts via the Groq worker. Keep the
        // fields on the summary for backward-compatibility with the dashboard
        // / persisted data, but populate with neutral defaults.
        val moodLabel = MoodSummarizer.Label.NEUTRAL.name
        val moodNegativeShare = 0f
        val moodSampleCount = 0

        // ---- 5. Sleep + motion anomalies ----
        val featureStore = ServiceLocator.dailyFeatureStore
        val today = featureStore.load(featureStore.todayKey())
        val sleepHours = today?.sleepDurationHrs ?: 0f
        val motionAnomalies = ServiceLocator.metricsBus.state.value.motionAnomaliesToday

        // ---- 6. Voice tone ----
        // Accept any voice sample taken within the window OR up to 6 hours
        // before windowEnd. The strict `in window` check used to drop a voice
        // capture made 5 min before "Start window" — making `voiceSentiment`
        // null even though we had a perfectly good recent reading.
        val voiceMaxAgeMs = 6L * 60 * 60 * 1000
        val voiceSnap = ServiceLocator.voiceCache.load()
        val voiceSentiment: Float? = voiceSnap?.takeIf { v ->
            val ts = v.timestampMs
            ts in windowStartMs..windowEndMs ||
                (ts in (windowEndMs - voiceMaxAgeMs)..windowEndMs)
        }?.takeIf { it.hasTranscript }?.sentimentScore

        // ---- 7. Calls ----
        val calls = runCatching { CallLogCollector(ctx).snapshotForDay(windowStartMs, windowEndMs) }
            .getOrNull()
        val callMinutes = calls?.totalTalkMin ?: 0
        val callCount = calls?.callCount ?: 0
        // talkingMinutes = calls + meetings (MEETING events). Meeting detector
        // is a stub for v1; for now talkingMinutes == call minutes.
        val meetingMinutes = events.filter { it.type == DailyEvent.Type.MEETING }
            .sumOf { it.durationMin }
        val talkingMinutes = callMinutes + meetingMinutes

        // ---- 8. App usage ----
        // Use the exact window range (NOT just "today's calendar day") so
        // per-app minutes reflect the actual 1 h / 3 h / 24 h / 48 h period.
        val usage = runCatching {
            UsageStatsCollector(ctx).snapshotRange(windowStartMs, windowEndMs)
        }.getOrNull()
        val totalPhoneUseMinutes = usage?.totalForegroundMin ?: 0
        val socialAppRatio = usage?.socialAppRatio ?: 0f

        // Per-category minutes — prefer APP_USE timeline events (written by
        // the nightly worker) when present; otherwise classify the live
        // perAppMinutes map from UsageStatsCollector via AppCategoryMap so
        // sub-day windows ending before midnight still get real numbers.
        val socialFromEvents = events
            .filter { it.type == DailyEvent.Type.APP_USE
                && it.metadata["category"] == AppCategoryMap.Category.SOCIAL.name }
            .sumOf { it.durationMin }
        val videoFromEvents = events
            .filter { it.type == DailyEvent.Type.APP_USE
                && it.metadata["category"] == AppCategoryMap.Category.VIDEO.name }
            .sumOf { it.durationMin }
        val socialMediaMinutes = if (socialFromEvents > 0) socialFromEvents
            else usage?.perAppMinutes?.entries?.filter {
                AppCategoryMap.categoryOf(it.key) == AppCategoryMap.Category.SOCIAL ||
                AppCategoryMap.categoryOf(it.key) == AppCategoryMap.Category.MESSAGING
            }?.sumOf { it.value } ?: 0
        val videoMinutes = if (videoFromEvents > 0) videoFromEvents
            else usage?.perAppMinutes?.entries?.filter {
                AppCategoryMap.categoryOf(it.key) == AppCategoryMap.Category.VIDEO
            }?.sumOf { it.value } ?: 0

        // ---- 9. Routine analysis (uses device-local time) ----
        val routine = RoutineAnalyzer.analyze(
            ctx,
            bedtimeMs = today?.bedtimeMs?.takeIf { it > 0L },
            wakeMs = today?.wakeMs?.takeIf { it > 0L },
            sleepHours = sleepHours,
        )

        // ---- 10. Blood pressure (manual entry, latest if within 7 days) ----
        val bp = ServiceLocator.bloodPressureStore.latest()?.takeIf { r ->
            System.currentTimeMillis() - r.tsMs <= 7L * 24 * 60 * 60 * 1000
        }

        // ---- 11. Per-point activity timeline ----
        val pts = ServiceLocator.activityPointLogger
            .pointsBetween(windowStartMs, windowEndMs)
        val topPoints = ServiceLocator.activityPointLogger.topLabels(pts, maxLabels = 30)

        return WindowSummaryStore.WindowSummary(
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            periodHours = periodHours,
            walkingMinutes = walkingMinutes,
            walkingBouts = walkingBouts,
            totalSteps = totalSteps,
            locationFixes = locationFixes,
            locationEntropy = locationEntropy,
            radiusKm = radiusKm,
            heartRateAvg = heartRateAvg,
            heartRateMin = heartRateMin,
            heartRateMax = heartRateMax,
            moodLabel = moodLabel,
            moodNegativeShare = moodNegativeShare,
            moodSampleCount = moodSampleCount,
            sleepHours = sleepHours,
            motionAnomalies = motionAnomalies,
            voiceSentiment = voiceSentiment,
            talkingMinutes = talkingMinutes,
            callMinutes = callMinutes,
            callCount = callCount,
            totalPhoneUseMinutes = totalPhoneUseMinutes,
            socialMediaMinutes = socialMediaMinutes,
            videoMinutes = videoMinutes,
            socialAppRatio = socialAppRatio,
            routineScore = routine.score,
            wakeTimeLocal = routine.wakeLocal,
            bedTimeLocal = routine.bedLocal,
            routineFeedback = routine.feedback,
            bpSystolic = bp?.systolic,
            bpDiastolic = bp?.diastolic,
            actionPointCount = pts.size,
            topActionPoints = topPoints,
        )
    }

    /** Loads events from each day touched by the window, keeping only those
     *  whose own time range overlaps `[startMs, endMs]`. */
    private fun collectEventsInWindow(startMs: Long, endMs: Long): List<DailyEvent> {
        val store = ServiceLocator.dailyEventStore
        // Walk through each calendar day from start to end (windows ≤ 72 h
        // means at most 4 day-files).
        val out = mutableListOf<DailyEvent>()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = startMs }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        var dayMs = cal.timeInMillis
        val oneDay = 24L * 60 * 60 * 1000
        while (dayMs <= endMs) {
            val key = store.keyFor(dayMs)
            for (ev in store.load(key)) {
                if (ev.endMs >= startMs && ev.startMs <= endMs) out += ev
            }
            dayMs += oneDay
        }
        return out
    }
}



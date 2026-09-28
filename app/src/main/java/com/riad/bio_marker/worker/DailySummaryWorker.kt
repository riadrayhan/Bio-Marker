package com.riad.bio_marker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.R
import com.riad.bio_marker.collectors.CallLogCollector
import com.riad.bio_marker.collectors.SleepInferenceEngine
import com.riad.bio_marker.collectors.TypingSessionAggregator
import com.riad.bio_marker.collectors.UsageStatsCollector
import com.riad.bio_marker.core.DailySummaryStore
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.ml.EventMoodTagger
import com.riad.bio_marker.rules.RecommendationEngine
import com.riad.bio_marker.rules.RuleEngine

/**
 * Nightly rule-based summary builder.
 *
 *  1. Loads the user's daily targets ([com.riad.bio_marker.core.UserGoalStore]).
 *  2. Collects today's actuals:
 *      - phone usage hrs from [UsageStatsCollector]
 *      - walking minutes derived from today's step count (≈100 steps/min)
 *      - talking minutes from [CallLogCollector]
 *      - sleep hours from [SleepInferenceEngine] (which reuses the
 *        already-fused estimate in [com.riad.bio_marker.core.DailyFeatureStore]).
 *  3. Runs [RuleEngine] to classify each metric into LESS / NORMAL / MORE
 *     and produce an overall GOOD / MIXED / ATTENTION verdict.
 *  4. Persists the result via [DailySummaryStore] and posts a notification.
 *
 * Chained from `BiomarkerApp.scheduleNightlySummary()` to run after
 * [DailyAggregationWorker] (which is what populates the feature store).
 */
class DailySummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val ctx = applicationContext

        // ---- Sleep ----
        val sleep = SleepInferenceEngine(ctx)
            .estimateForLastNight(ServiceLocator.dailyFeatureStore)
            .durationHrs

        // ---- Phone usage ----
        val usage = try { UsageStatsCollector(ctx).snapshot() } catch (_: Throwable) { null }
        val phoneHrs = (usage?.totalForegroundMin ?: 0) / 60f

        // ---- Walking (prefer real WALK timeline events; fallback to steps) ----
        val store = ServiceLocator.dailyFeatureStore
        val today = store.load(store.todayKey())
        val stepsToday = today?.stepCount ?: ServiceLocator.metricsBus.state.value.stepsToday
        val eventStore = ServiceLocator.dailyEventStore
        val todayEvents = try { eventStore.load(eventStore.todayKey()) } catch (_: Throwable) { emptyList() }
        val walkMinFromEvents = todayEvents
            .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.WALK }
            .sumOf { ((it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L).toInt() }
        val walkMinFromSteps = (stepsToday / STEPS_PER_MINUTE).coerceAtLeast(0)
        val walkMin = maxOf(walkMinFromEvents, walkMinFromSteps)

        // ---- Talking (calls + messenger / WhatsApp foreground) ----
        // We treat *any* outgoing conversation channel as "talking" so the
        // metric stops being permanently 0 for users who chat instead of
        // making phone calls. Three signals are fused:
        //   1. CallLog totals (post-hoc, requires READ_CALL_LOG).
        //   2. Live CALL timeline events from CallSessionCollector.
        //   3. Messaging-app foreground minutes (WhatsApp, Messenger,
        //      Telegram, Imo, Viber, Discord) from APP_USE events.
        val calls = try { CallLogCollector(ctx).snapshotForToday() } catch (_: Throwable) { null }
        val callMinFromLog = calls?.totalTalkMin ?: 0
        val callMinFromEvents = todayEvents
            .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.CALL }
            .sumOf { ((it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L).toInt() }
        val messagingMin = run {
            val fromEvents = todayEvents
                .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.APP_USE &&
                    (it.metadata["category"] == com.riad.bio_marker.core.AppCategoryMap.Category.MESSAGING.name ||
                     it.metadata["category"] == com.riad.bio_marker.core.AppCategoryMap.Category.SOCIAL.name) }
                .sumOf { ((it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L).toInt() }
            // APP_USE events are only written *after* this worker finishes its
            // first phase (see writeAppSessionsToTimeline below), so on the
            // very first run of the day fall back to live UsageStatsCollector.
            val fromUsage = usage?.perAppMinutes?.entries?.filter {
                com.riad.bio_marker.core.AppCategoryMap.categoryOf(it.key) ==
                    com.riad.bio_marker.core.AppCategoryMap.Category.MESSAGING ||
                com.riad.bio_marker.core.AppCategoryMap.categoryOf(it.key) ==
                    com.riad.bio_marker.core.AppCategoryMap.Category.SOCIAL
            }?.sumOf { it.value } ?: 0
            maxOf(fromEvents, fromUsage)
        }
        val talkMin = maxOf(callMinFromLog, callMinFromEvents) + messagingMin

        val rule = RuleEngine.evaluate(ctx, sleep, walkMin, phoneHrs, talkMin)

        // Conversation-tone line: derived from today's voice sentiment cache
        // (PassiveVoiceCollector accumulates word-level sentiment across the
        // day from phone calls + WhatsApp / Messenger voice messages) plus
        // any face-mood-tagged CALL events. We append it to the TALK metric
        // message so it appears under the Summary tab's "talking" card.
        val toneLine = buildConversationToneLine(ctx, todayEvents)
        val ruleMessages = rule.messages().toMutableList()
        if (toneLine.isNotBlank() && ruleMessages.size >= 4) {
            ruleMessages[3] = ruleMessages[3].trim() + "\n" + toneLine
        }

        val summary = DailySummaryStore.Summary(
            date = ServiceLocator.dailySummaryStore.todayKey(),
            sleepActualHrs = sleep,
            walkingActualMin = walkMin,
            phoneUsageActualHrs = phoneHrs,
            talkingActualMin = talkMin,
            sleepZone = rule.sleep.zone,
            walkZone = rule.walk.zone,
            phoneZone = rule.phone.zone,
            talkZone = rule.talk.zone,
            overall = rule.overall,
            messages = ruleMessages,
            createdAt = System.currentTimeMillis(),
        )
        ServiceLocator.dailySummaryStore.save(summary)
        ServiceLocator.dailySummaryStore.pruneOld()

        // ---- Per-event timeline: app sessions + mood tagging + narrative ----
        // Push the day's foreground app sessions into the timeline so the
        // narrator has them to work with. (Walk / call / typing events are
        // already streamed live from their collectors.)
        try {
            usage?.sessions?.let {
                UsageStatsCollector.writeAppSessionsToTimeline(it,
                    ServiceLocator.dailyEventStore.todayKey())
            }
        } catch (_: Throwable) { /* keep summary even if timeline write fails */ }
        // Flush any in-progress typing session so it lands in today's events.
        try { TypingSessionAggregator.flush() } catch (_: Throwable) {}

        val dateKey = ServiceLocator.dailyEventStore.todayKey()
        // Tag each event with a mood label (face / voice / typing heuristics).
        try { EventMoodTagger.tagDay(dateKey) } catch (_: Throwable) {}

        // Build the narrative report (per-event notes + recommendations).
        try {
            val events = ServiceLocator.dailyEventStore.load(dateKey)
            val report = RecommendationEngine.build(dateKey, events, summary)
            ServiceLocator.dailyReportStore.save(report)
            ServiceLocator.dailyReportStore.pruneOld()
            ServiceLocator.dailyEventStore.pruneOld()
        } catch (_: Throwable) { /* non-fatal */ }

        notify(ctx, rule.headline)
        Result.success()
    } catch (_: Throwable) {
        Result.retry()
    }

    /**
     * One-line "today's conversation tone" verdict appended to the TALK
     * metric in the Summary tab. Fuses:
     *   - The voice-sentiment scores cached by [com.riad.bio_marker.collectors.PassiveVoiceCollector]
     *     over the day (positive vs negative word ratio from on-device
     *     SpeechRecognizer transcripts of phone / WhatsApp / Messenger talk).
     *   - The dominant mood label among today's tagged CALL events
     *     (set by [com.riad.bio_marker.ml.EventMoodTagger] from facial +
     *     voice cues captured during the call).
     * Returns an empty string when there is no voice / call signal yet, so
     * the existing TALK message renders unchanged.
     */
    private fun buildConversationToneLine(
        ctx: Context,
        todayEvents: List<com.riad.bio_marker.core.DailyEvent>,
    ): String {
        val voice = runCatching { ServiceLocator.voiceCache.load() }.getOrNull()
        val sentiment = voice?.takeIf { it.hasTranscript }?.sentimentScore
        val callMoods = todayEvents
            .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.CALL }
            .mapNotNull { it.moodLabel?.takeIf { l -> l.isNotBlank() } }
        val dominantMood = callMoods
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        if (sentiment == null && dominantMood == null) return ""

        val toneRes = when {
            sentiment != null && sentiment >= 0.25f -> R.string.tone_positive
            sentiment != null && sentiment <= -0.25f -> R.string.tone_negative
            sentiment != null -> R.string.tone_neutral
            else -> when (dominantMood?.uppercase()) {
                "HAPPY", "POSITIVE" -> R.string.tone_positive
                "ANGRY", "SAD", "NEGATIVE" -> R.string.tone_negative
                else -> R.string.tone_neutral
            }
        }
        return ctx.getString(R.string.tone_line, ctx.getString(toneRes))
    }

    private fun notify(ctx: Context, headline: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.summary_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_dashboard)
            .setContentTitle(ctx.getString(R.string.summary_notif_title))
            .setContentText(headline)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        const val STEPS_PER_MINUTE = 100
        private const val CHANNEL_ID = "daily_summary"
        private const val NOTIF_ID = 4321
    }
}


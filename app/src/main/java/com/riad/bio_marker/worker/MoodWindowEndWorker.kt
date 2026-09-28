package com.riad.bio_marker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.R
import com.riad.bio_marker.core.MoodTimelineAnalyzer
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.VoiceTranscriptStore
import com.riad.bio_marker.ml.GroqAgentClient
import com.riad.bio_marker.ml.MoodSummarizer
import com.riad.bio_marker.ui.MoodSummaryActivity

/**
 * Runs once when the user's monitoring window expires.
 *
 * Reads every [com.riad.bio_marker.core.MoodTimelineStore.MoodSample] that
 * was appended during the window, asks [MoodSummarizer] for a final verdict,
 * persists it via [com.riad.bio_marker.core.MoodVerdictStore], and posts a
 * notification that opens [MoodSummaryActivity].
 *
 * Scheduled in [com.riad.bio_marker.ui.ToolsFragment.startMonitoringWindow]
 * with `setInitialDelay(periodHours, TimeUnit.HOURS)`.
 */
class MoodWindowEndWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val windowStartMs = inputData.getLong(KEY_WINDOW_START, 0L)
        val windowEndMs = inputData.getLong(KEY_WINDOW_END, System.currentTimeMillis())
        if (windowStartMs == 0L) return Result.success()

        val samples = ServiceLocator.moodTimelineStore.samplesFor(windowStartMs)
        // Face-based mood timeline removed — when there are no face samples
        // (which is now always the case), skip the per-window face-mood verdict
        // and notification entirely. The Groq voice-transcript analysis
        // below still produces a fine-grained verdict from speech.
        var verdict: MoodSummarizer.Verdict? = null
        if (samples.isNotEmpty()) {
            val v = MoodSummarizer.summarize(samples)
            verdict = v
            val durations = MoodTimelineAnalyzer.breakdown(samples)
            val transitions = MoodTimelineAnalyzer.transitions(samples)
            val hrAvg = ServiceLocator.activeHeartRateCollector?.stats()
                ?.takeIf { it.n > 0 }?.avg
            ServiceLocator.moodVerdictStore.save(
                v, windowStartMs, windowEndMs,
                durationsMs = durations,
                transitions = transitions,
                heartRateAvg = hrAvg,
            )
        }
        // Start the next window with a clean HR average instead of carrying
        // forward stale samples from the previous window.
        ServiceLocator.activeHeartRateCollector?.reset()

        // Per-utterance Groq mood-timeline analysis. PassiveVoiceCollector
        // appended every recognised utterance (Bangla + English) into
        // VoiceTranscriptStore during the window; ask Groq to produce a
        // fine-grained verdict like "good for 50 min, angry last 10 min".
        // Best-effort — silently skipped if the user has no transcripts or
        // the GROQ_API_KEY is unset.
        var aiVerdict: VoiceTranscriptStore.AiVerdict? = null
        runCatching {
            val transcripts = ServiceLocator.voiceTranscriptStore
                .entriesFor(windowStartMs)
            if (transcripts.isNotEmpty()) {
                val ai = GroqAgentClient.analyzeTranscripts(
                    entries = transcripts,
                    windowStartMs = windowStartMs,
                    windowEndMs = windowEndMs,
                )
                if (ai != null) {
                    val v = VoiceTranscriptStore.AiVerdict(
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        createdAtMs = System.currentTimeMillis(),
                        headline = ai.headline,
                        analysis = ai.analysis,
                        recommendations = ai.recommendations,
                        engine = ai.engine,
                    )
                    aiVerdict = v
                    ServiceLocator.voiceTranscriptStore.saveVerdict(v)
                }
            }
        }

        // Always surface a result the user can tap into: prefer the AI
        // voice verdict (the real "how was your mood" output), and fall back
        // to the legacy face verdict only if no AI verdict was produced.
        notifyUser(ctx, verdict, aiVerdict)
        return Result.success()
    }

    private fun notifyUser(
        ctx: Context,
        verdict: MoodSummarizer.Verdict?,
        aiVerdict: VoiceTranscriptStore.AiVerdict?,
    ) {
        // Nothing worth showing — no AI verdict and no face verdict.
        if (verdict == null && aiVerdict == null) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.mood_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        // Title + body: AI verdict wins when present, otherwise the face label.
        val title: String
        val body: String
        if (aiVerdict != null) {
            title = ctx.getString(R.string.mood_notif_ai_title)
            body = ctx.getString(R.string.mood_notif_ai_message, aiVerdict.headline)
        } else {
            val labelText = ctx.getString(MoodLabelStrings.titleRes(verdict!!.dominant))
            title = ctx.getString(R.string.mood_notif_title)
            body = ctx.getString(R.string.mood_notif_message, labelText)
        }
        val openIntent = Intent(ctx, MoodSummaryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_dashboard)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        const val KEY_WINDOW_START = "window_start_ms"
        const val KEY_WINDOW_END = "window_end_ms"
        private const val CHANNEL_ID = "mood_summary"
        private const val NOTIF_ID = 4555
    }
}

/** Single source of truth for label → string resource mapping. */
internal object MoodLabelStrings {
    fun titleRes(label: MoodSummarizer.Label): Int = when (label) {
        MoodSummarizer.Label.HAPPY    -> R.string.mood_label_happy
        MoodSummarizer.Label.NEUTRAL  -> R.string.mood_label_neutral
        MoodSummarizer.Label.TIRED    -> R.string.mood_label_tired
        MoodSummarizer.Label.STRESSED -> R.string.mood_label_stressed
        MoodSummarizer.Label.SAD      -> R.string.mood_label_sad
    }
    fun adviceRes(label: MoodSummarizer.Label): Int = when (label) {
        MoodSummarizer.Label.HAPPY    -> R.string.mood_advice_happy
        MoodSummarizer.Label.NEUTRAL  -> R.string.mood_advice_neutral
        MoodSummarizer.Label.TIRED    -> R.string.mood_advice_tired
        MoodSummarizer.Label.STRESSED -> R.string.mood_advice_stressed
        MoodSummarizer.Label.SAD      -> R.string.mood_advice_sad
    }
}


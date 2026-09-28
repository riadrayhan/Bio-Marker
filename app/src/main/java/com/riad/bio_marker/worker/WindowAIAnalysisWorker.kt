package com.riad.bio_marker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.riad.bio_marker.BuildConfig
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.WindowSummaryBuilder
import com.riad.bio_marker.ml.GroqAgentClient
import java.util.concurrent.TimeUnit

/**
 * Runs **once** at the end of a user-defined monitoring window
 * (1 / 5 / 24 / 48 / 72 h). Pipeline:
 *
 *   1. [WindowSummaryBuilder.build] — aggregate every signal for the window.
 *   2. Persist the un-AI'd summary immediately so the dashboard has *something*
 *      to show even if the network call fails.
 *   3. [GroqAgentClient.analyze] — send the summary to Groq, get back
 *      headline + analysis + recommendations.
 *   4. Persist the AI-augmented summary; post a notification.
 *
 * Scheduled by `ToolsFragment.scheduleMoodWindowEndWorker(...)` immediately
 * after [MoodWindowEndWorker]. Both run with the same `KEY_WINDOW_START` /
 * `KEY_WINDOW_END` input data so they agree on which window they're closing.
 */
class WindowAIAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val startMs = inputData.getLong(MoodWindowEndWorker.KEY_WINDOW_START, 0L)
        val endMs = inputData.getLong(MoodWindowEndWorker.KEY_WINDOW_END, System.currentTimeMillis())
        val periodHours = inputData.getInt(KEY_PERIOD_HOURS, 0)
            .takeIf { it > 0 }
            ?: ((endMs - startMs) / (60 * 60 * 1000L)).toInt().coerceAtLeast(1)
        if (startMs == 0L) return Result.success()

        Log.i(TAG, "doWork: window=$startMs..$endMs ($periodHours h) " +
            "groqKeyLen=${BuildConfig.GROQ_API_KEY.length}")

        val store = ServiceLocator.windowSummaryStore
        // 1+2: build & save baseline summary so the dashboard has data even
        // if the AI call is offline / rate-limited.
        val baseline = WindowSummaryBuilder.build(ctx, startMs, endMs, periodHours)
        store.save(baseline)

        // 3: optional AI enrichment.
        val ai = runCatching { GroqAgentClient.analyze(baseline) }.getOrNull()
        if (ai == null) {
            // No AI verdict yet — retry with backoff so a transient flaky
            // network or rate-limit doesn't permanently leave the user
            // looking at "Window analysis" placeholder text.
            if (runAttemptCount < 3) {
                Log.w(TAG, "AI call returned null — retrying (attempt $runAttemptCount)")
                return Result.retry()
            }
            Log.w(TAG, "AI call returned null after $runAttemptCount retries; giving up.")
            notify(ctx, ctx.getString(R.string.window_ai_default_headline))
            return Result.success()
        }
        val finalSummary = baseline.copy(
            aiHeadline = ai.headline,
            aiAnalysis = ai.analysis,
            aiRecommendations = ai.recommendations,
            aiEngine = ai.engine,
            aiCreatedAtMs = System.currentTimeMillis(),
        )
        store.save(finalSummary)
        store.pruneOld()

        notify(ctx, finalSummary.aiHeadline ?: ctx.getString(R.string.window_ai_default_headline))
        return Result.success()
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
                    ctx.getString(R.string.window_ai_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_dashboard)
            .setContentTitle(ctx.getString(R.string.window_ai_notif_title))
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(headline))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        private const val TAG = "WindowAIAnalysisWorker"
        const val KEY_PERIOD_HOURS = "window_period_hours"
        private const val CHANNEL_ID = "window_ai_summary"
        private const val NOTIF_ID = 4789

        /** Builds a one-shot AI analysis request for [start, end]. The request
         *  has the network constraint + exponential backoff baked in so a
         *  transient offline state doesn't silently lose the verdict. */
        fun buildOneShot(
            startMs: Long,
            endMs: Long,
            periodHours: Int,
        ): OneTimeWorkRequest {
            val data = androidx.work.Data.Builder()
                .putLong(MoodWindowEndWorker.KEY_WINDOW_START, startMs)
                .putLong(MoodWindowEndWorker.KEY_WINDOW_END, endMs)
                .putInt(KEY_PERIOD_HOURS, periodHours)
                .build()
            return OneTimeWorkRequestBuilder<WindowAIAnalysisWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        }
    }
}


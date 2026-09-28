package com.riad.bio_marker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.ml.insights.MLInsightsRunner

/**
 * Nightly ML inference worker.
 *
 * Loads the last 30 [com.riad.bio_marker.core.DailySummaryStore.Summary]
 * rows and runs the three on-device insight stages via [MLInsightsRunner]:
 * trend anomaly detection, personalised target suggestions, and weekly
 * pattern clustering.
 *
 * Chained after [DailySummaryWorker] so today's row is already persisted.
 * Output is stored in [com.riad.bio_marker.core.MLInsightStore] and surfaced
 * by `SummaryFragment`. If the model flags an anomaly we also post a
 * gentle notification.
 */
class MLInferenceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val ctx = applicationContext
        val history = ServiceLocator.dailySummaryStore.lastN(30)
        if (history.isEmpty()) {
            Result.success()
        } else {
            val insight = MLInsightsRunner(ctx).run(history)
            ServiceLocator.mlInsightStore.save(insight)
            ServiceLocator.mlInsightStore.pruneOld()

            if (insight.anomalous && insight.anomalousMetrics.isNotEmpty()) {
                notify(ctx, insight)
            }
            Result.success()
        }
    } catch (_: Throwable) {
        Result.retry()
    }

    private fun notify(ctx: Context, insight: com.riad.bio_marker.core.MLInsightStore.Insight) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.ml_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val text = ctx.getString(
            R.string.ml_anomaly_message,
            insight.anomalousMetrics.joinToString(", "),
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_dashboard)
            .setContentTitle(ctx.getString(R.string.ml_anomaly_title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "ml_insights"
        private const val NOTIF_ID = 4322
    }
}


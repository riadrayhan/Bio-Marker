package com.riad.bio_marker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.StressDetector
import com.riad.bio_marker.ui.VitalsCheckInActivity

/**
 * Runs roughly every 15 minutes while the BiomarkerService is active.
 *
 *  - Asks [StressDetector] whether the user looks visibly stressed right now.
 *  - If the score is ≥ [StressDetector.HIGH_THRESHOLD] AND we haven't fired
 *    a stress notification in the last [COOLDOWN_MINUTES], we post a
 *    high-priority "Quick vitals check?" notification that opens
 *    [VitalsCheckInActivity].
 *
 * The cooldown protects users from being spammed if they're stressed for
 * an extended period (e.g. a difficult workday).
 */
class StressMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = ServiceLocator.metricsBus.state.value
        // Only nudge while the user has explicitly started a monitoring window
        // (otherwise we'd nag them all day even when they didn't opt in).
        val session = ServiceLocator.monitoringStore.current()
        if (!s.serviceRunning || session == null || session.isExpired()) {
            return Result.success()
        }

        val voice = ServiceLocator.voiceCache.load()
        val res = StressDetector.analyze(s, voice)
        Log.i(TAG, "stress score=${"%.2f".format(res.score)} reasons=${res.reasons}")

        ServiceLocator.activityPointLogger.log(
            "stress.scan",
            "stress score ${"%.2f".format(res.score)}",
        )

        if (res.score < StressDetector.HIGH_THRESHOLD) return Result.success()

        // Cooldown — don't fire more than once per [COOLDOWN_MINUTES].
        val prefs = ctx.getSharedPreferences("biomarker_stress", Context.MODE_PRIVATE)
        val lastFired = prefs.getLong(K_LAST_FIRED, 0L)
        val now = System.currentTimeMillis()
        if (now - lastFired < COOLDOWN_MINUTES * 60_000L) {
            Log.i(TAG, "skipping stress notif — cooldown")
            return Result.success()
        }
        prefs.edit().putLong(K_LAST_FIRED, now).apply()

        notifyStress(ctx, res)
        ServiceLocator.activityPointLogger.log(
            "stress.notify",
            "vitals-check nudge sent (${res.reasons.joinToString("·")})",
        )
        return Result.success()
    }

    private fun notifyStress(ctx: Context, res: StressDetector.Result) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.stress_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = ctx.getString(R.string.stress_channel_desc)
                }
            )
        }

        // Tap → opens the vitals check-in screen so the user can record HR /
        // BP / breath, which then feed Groq for a personalised relax plan.
        val openIntent = Intent(ctx, VitalsCheckInActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putStringArrayListExtra(
                VitalsCheckInActivity.EXTRA_REASONS,
                ArrayList(res.reasons),
            )
        val tapPi = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val body = if (res.reasons.isEmpty())
            ctx.getString(R.string.stress_notif_body_generic)
        else ctx.getString(R.string.stress_notif_body, res.reasons.joinToString(" · "))

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ctx.getString(R.string.stress_notif_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, ctx.getString(R.string.stress_action_check), tapPi)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    companion object {
        private const val TAG = "StressMonitorWorker"
        private const val CHANNEL_ID = "biomarker_stress"
        private const val NOTIF_ID = 4900
        private const val K_LAST_FIRED = "last_fired_ms"
        const val COOLDOWN_MINUTES = 45L
    }
}


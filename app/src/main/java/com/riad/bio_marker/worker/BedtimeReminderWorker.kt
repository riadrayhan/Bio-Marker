package com.riad.bio_marker.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.MainActivity
import com.riad.bio_marker.R
import com.riad.bio_marker.collectors.UsageStatsCollector
import com.riad.bio_marker.core.ServiceLocator
import java.util.Calendar

/**
 * Bedtime nudge engine. Two notifications are scheduled per evening, both
 * gated on (a) the user actually having an active monitoring window, and
 * (b) the user's local clock — 22:30 onwards but before 23:30:
 *
 *   1. **Sleep-soon** — fired once per evening at the first run after 22:30:
 *      "It's almost 11 PM. Sleep before 11 PM is best for your health."
 *
 *   2. **Phone-down** — fired once per evening if at 22:30+ the user is
 *      *currently* using a social-media / messaging app: "You're scrolling
 *      30 min before bedtime. Put the phone down and start winding down."
 *
 * The worker runs every 15 minutes (WorkManager's lower bound) so it
 * actually triggers within the 22:30–23:30 window. Idempotency is enforced
 * by a SharedPreferences flag keyed on the local date so the user gets at
 * most one of each notification per evening.
 */
class BedtimeReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // 1. Only fire while the user has explicitly opted into monitoring
        //    (1.5h / 1d / 2d session). Without an active window we never
        //    touch the notification surface — matches the user's request:
        //    "ei sob kichu measure/monitor korbe jokhon user tar time start korbe".
        val session = ServiceLocator.monitoringStore.current() ?: return Result.success()
        if (session.isExpired()) return Result.success()

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        // Active window: 22:30 → 23:30 local time. Outside of this we
        // exit cleanly so the worker stays cheap to run every 15 min.
        val afterStart = hour > 22 || (hour == 22 && minute >= 30)
        val beforeEnd = hour < 23 || (hour == 23 && minute <= 30)
        if (!afterStart || !beforeEnd) return Result.success()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey(cal)

        ensureChannel(ctx)

        // 2. Sleep-soon reminder (once per evening).
        if (prefs.getString(K_LAST_SLEEP_DATE, null) != today) {
            postNotification(
                ctx,
                NOTIF_SLEEP,
                ctx.getString(R.string.bedtime_sleep_title),
                ctx.getString(R.string.bedtime_sleep_body),
            )
            prefs.edit().putString(K_LAST_SLEEP_DATE, today).apply()
        }

        // 3. Phone-down reminder (once per evening, only if user is on a
        //    social app right now).
        if (prefs.getString(K_LAST_PHONE_DATE, null) != today) {
            if (isUsingSocialAppNow(ctx, now)) {
                postNotification(
                    ctx,
                    NOTIF_PHONE_DOWN,
                    ctx.getString(R.string.bedtime_phone_title),
                    ctx.getString(R.string.bedtime_phone_body),
                )
                prefs.edit().putString(K_LAST_PHONE_DATE, today).apply()
            }
        }

        return Result.success()
    }

    /**
     * Returns true if the user has been in the foreground of a known
     * social / messaging app at any point in the last 5 minutes. We use
     * the existing [UsageStatsCollector.snapshotRange] so the social-app
     * package list stays in one place.
     */
    private fun isUsingSocialAppNow(ctx: Context, nowMs: Long): Boolean {
        val collector = UsageStatsCollector(ctx)
        if (!collector.hasPermission()) return false
        val from = nowMs - 5 * 60_000L
        val snap = collector.snapshotRange(from, nowMs) ?: return false
        // Any non-zero ratio over the last 5 min means the user was in a
        // social app within that window.
        return snap.socialAppRatio > 0.05f
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.bedtime_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.getString(R.string.bedtime_channel_desc)
            }
        )
    }

    private fun postNotification(ctx: Context, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, n)
    }

    private fun todayKey(cal: Calendar): String =
        "%04d%02d%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )

    companion object {
        const val UNIQUE_NAME = "bedtime-reminder"
        private const val CHANNEL_ID = "bedtime_reminder"
        private const val NOTIF_SLEEP = 7711
        private const val NOTIF_PHONE_DOWN = 7712
        private const val PREFS = "biomarker_bedtime"
        private const val K_LAST_SLEEP_DATE = "last_sleep_date"
        private const val K_LAST_PHONE_DATE = "last_phone_date"
    }
}


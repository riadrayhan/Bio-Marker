package com.riad.bio_marker.ml

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.riad.bio_marker.MainActivity
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.monitoring.CrisisRules

/**
 * Posts a supportive crisis-class notification when *any* of the five
 * [CrisisRules.Trigger]s fire, OR as a back-stop when the latest
 * [RiskResult.score] crosses [HIGH_RISK_THRESHOLD].
 */
class CrisisNotifier(private val context: Context) {

    fun maybeNotify(result: RiskResult) {
        // Always update the rolling history first so triggers can see today's score.
        ServiceLocator.riskHistoryStore.append(result.score)

        val triggers = evaluateTriggers()
        if (triggers.isEmpty() && result.score < HIGH_RISK_THRESHOLD) return

        ensureChannel()
        post(triggers)

        // Tell the nudge engine to surface a crisis-support tap on its next run,
        // and pull the next therapy reminder forward by 48 h (no-op if none scheduled).
        ServiceLocator.nudgeStore.requestCrisisFollowUp()
        ServiceLocator.therapyReminder.advanceBy48h()
    }

    private fun evaluateTriggers(): List<CrisisRules.Trigger> = try {
        val dailies = ServiceLocator.dailyFeatureStore.lastN(7)
        val voiceBaseline = ServiceLocator.userBaseline
            .takeIf { it.isWarm("voice_energy_mean") }
            ?.mean("voice_energy_mean")
        val snap = CrisisRules.fromDailies(dailies, voiceBaseline)
            .copy(recentRiskScores = ServiceLocator.riskHistoryStore.dailyPeaks(7))
        CrisisRules.evaluate(snap)
    } catch (_: Throwable) { emptyList() }

    private fun post(triggers: List<CrisisRules.Trigger>) {
        val dialPi = PendingIntent.getActivity(
            context, 1,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$HELPLINE_NUMBER")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openAppPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bigText = buildString {
            append(context.getString(R.string.crisis_big_text))
            if (triggers.isNotEmpty()) {
                append("\n\n").append(context.getString(R.string.crisis_reasons_prefix))
                triggers.forEach { append("\n• ").append(reasonLabel(it)) }
            }
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.crisis_title))
            .setContentText(context.getString(R.string.crisis_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppPi)
            .addAction(0, context.getString(R.string.crisis_action_call), dialPi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun reasonLabel(t: CrisisRules.Trigger): String = context.getString(
        when (t) {
            CrisisRules.Trigger.SUSTAINED_HIGH_RISK -> R.string.crisis_reason_sustained
            CrisisRules.Trigger.RAPID_DROP          -> R.string.crisis_reason_rapid_drop
            CrisisRules.Trigger.DEEP_NIGHT_USAGE    -> R.string.crisis_reason_deep_night
            CrisisRules.Trigger.SEVERE_SLEEP_LOSS   -> R.string.crisis_reason_sleep
            CrisisRules.Trigger.VOICE_COLLAPSE      -> R.string.crisis_reason_voice
        }
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.crisis_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.crisis_channel_desc) }
        )
    }

    companion object {
        const val HIGH_RISK_THRESHOLD = 0.85f
        // Kaan Pete Roi (Bangladesh): 24/7 emotional-support helpline.
        const val HELPLINE_NUMBER = "01779554391"
        private const val CHANNEL_ID = "biomarker_crisis"
        private const val NOTIF_ID = 9001
    }
}

package com.riad.bio_marker.core

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
import java.util.Calendar

/**
 * Builds and posts gentle / JITAI / crisis nudges. Two channels:
 *  - GENTLE (LOW priority) for VALIDATION, MICRO_ACTION, ENCOURAGEMENT,
 *    ACTIVITY, SLEEP, BREATHING, SOCIAL, CHECK_IN, PATTERN_REFLECTION
 *  - CRISIS (HIGH priority) only for CRISIS_SUPPORT, with a "Call helpline" action.
 *
 * For CHECK_IN nudges we attach **three emoji action buttons** (😊 😐 😔) that
 * dispatch broadcast intents handled by [MoodCheckInReceiver], recording the
 * tap as ground truth for future model fine-tuning.
 *
 * For PATTERN_REFLECTION we substitute the day-of-week and signal name into
 * a curious, non-accusatory body string.
 */
class NudgeNotifier(private val context: Context) {

    fun post(decision: NudgeEngine.Decision) {
        ensureChannels()
        val type = decision.type
        val notifId = NOTIF_ID_BASE + type.ordinal

        val title = context.getString(titleRes(type))
        val body = bodyText(decision)

        val tapPi = PendingIntent.getActivity(
            context, type.ordinal,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelFor(type))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(
                if (type == NudgeStore.Type.CRISIS_SUPPORT)
                    NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_LOW
            )

        // Spec: Check-in must show ONLY the question + three emoji buttons
        // (no instructional body text — "টাইপ করতে হয় না").
        if (type != NudgeStore.Type.CHECK_IN) {
            builder.setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        when (type) {
            NudgeStore.Type.CHECK_IN -> attachMoodActions(builder, notifId)
            NudgeStore.Type.CRISIS_SUPPORT -> attachHelplineAction(builder)
            else -> Unit
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, builder.build())
    }

    /** Backwards-compat single-type entrypoint used by older code paths. */
    fun post(type: NudgeStore.Type) = post(NudgeEngine.Decision(type))

    // ---------- copy ----------

    private fun titleRes(type: NudgeStore.Type): Int = when (type) {
        NudgeStore.Type.ENCOURAGEMENT -> R.string.nudge_encouragement_title
        NudgeStore.Type.ACTIVITY -> R.string.nudge_activity_title
        NudgeStore.Type.SLEEP -> R.string.nudge_sleep_title
        NudgeStore.Type.BREATHING -> R.string.nudge_breathing_title
        NudgeStore.Type.SOCIAL -> R.string.nudge_social_title
        NudgeStore.Type.CHECK_IN -> R.string.nudge_checkin_title
        NudgeStore.Type.CRISIS_SUPPORT -> R.string.nudge_crisis_title
        NudgeStore.Type.VALIDATION -> R.string.nudge_validation_title
        NudgeStore.Type.MICRO_ACTION -> R.string.nudge_micro_title
        NudgeStore.Type.PATTERN_REFLECTION -> R.string.nudge_pattern_title
    }

    private fun bodyText(decision: NudgeEngine.Decision): String {
        return when (decision.type) {
            NudgeStore.Type.PATTERN_REFLECTION -> {
                val p = decision.pattern
                if (p == null) context.getString(R.string.nudge_pattern_body_generic)
                else {
                    // "প্রতি <day><" রাতে" if sleep else ""> আপনার <signal> <কম/বেশি> হয়।"
                    val nightQual = if (p.signal == WeeklyPatternStore.Signal.SLEEP)
                        context.getString(R.string.nudge_pattern_night_qual)
                    else ""
                    val direction = context.getString(
                        if (p.direction == WeeklyPatternStore.Direction.LOW)
                            R.string.nudge_pattern_dir_low
                        else R.string.nudge_pattern_dir_high
                    )
                    context.getString(
                        R.string.nudge_pattern_body,
                        dayOfWeekLabel(p.dayOfWeek),
                        nightQual,
                        signalLabel(p.signal),
                        direction,
                    )
                }
            }
            else -> context.getString(bodyRes(decision.type))
        }
    }

    private fun bodyRes(type: NudgeStore.Type): Int = when (type) {
        NudgeStore.Type.ENCOURAGEMENT -> R.string.nudge_encouragement_body
        NudgeStore.Type.ACTIVITY -> R.string.nudge_activity_body
        NudgeStore.Type.SLEEP -> R.string.nudge_sleep_body
        NudgeStore.Type.BREATHING -> R.string.nudge_breathing_body
        NudgeStore.Type.SOCIAL -> R.string.nudge_social_body
        NudgeStore.Type.CHECK_IN -> R.string.nudge_checkin_body
        NudgeStore.Type.CRISIS_SUPPORT -> R.string.nudge_crisis_body
        NudgeStore.Type.VALIDATION -> R.string.nudge_validation_body
        NudgeStore.Type.MICRO_ACTION -> R.string.nudge_micro_body
        NudgeStore.Type.PATTERN_REFLECTION -> R.string.nudge_pattern_body_generic
    }

    private fun dayOfWeekLabel(dow: Int): String {
        val resId = when (dow) {
            Calendar.SUNDAY -> R.string.day_sun
            Calendar.MONDAY -> R.string.day_mon
            Calendar.TUESDAY -> R.string.day_tue
            Calendar.WEDNESDAY -> R.string.day_wed
            Calendar.THURSDAY -> R.string.day_thu
            Calendar.FRIDAY -> R.string.day_fri
            Calendar.SATURDAY -> R.string.day_sat
            else -> R.string.day_today
        }
        return context.getString(resId)
    }

    private fun signalLabel(signal: WeeklyPatternStore.Signal): String =
        context.getString(when (signal) {
            WeeklyPatternStore.Signal.SLEEP -> R.string.signal_sleep
            WeeklyPatternStore.Signal.STEPS -> R.string.signal_steps
            WeeklyPatternStore.Signal.WELLNESS -> R.string.signal_wellness
        })

    // ---------- actions ----------

    private fun attachMoodActions(builder: NotificationCompat.Builder, notifId: Int) {
        builder.addAction(
            0, "😊", moodPi(MoodCheckInReceiver.ACTION_GOOD, notifId, code = 7001)
        )
        builder.addAction(
            0, "😐", moodPi(MoodCheckInReceiver.ACTION_NEUTRAL, notifId, code = 7002)
        )
        builder.addAction(
            0, "😔", moodPi(MoodCheckInReceiver.ACTION_LOW, notifId, code = 7003)
        )
    }

    private fun moodPi(action: String, notifId: Int, code: Int): PendingIntent {
        val intent = Intent(context, MoodCheckInReceiver::class.java).apply {
            this.action = action
            putExtra(MoodCheckInReceiver.EXTRA_NOTIF_ID, notifId)
            // Make the intent unique so the system doesn't merge them.
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun attachHelplineAction(builder: NotificationCompat.Builder) {
        val helplinePi = PendingIntent.getActivity(
            context, 9001,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:09611677777")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(0, context.getString(R.string.crisis_action_call), helplinePi)
    }

    // ---------- channel ----------

    private fun channelFor(type: NudgeStore.Type): String =
        if (type == NudgeStore.Type.CRISIS_SUPPORT) CHANNEL_CRISIS else CHANNEL_GENTLE

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_GENTLE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GENTLE,
                    context.getString(R.string.nudge_channel_gentle_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.nudge_channel_gentle_desc)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_CRISIS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_CRISIS,
                    context.getString(R.string.crisis_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.crisis_channel_desc)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_GENTLE = "biomarker_nudges_gentle"
        private const val CHANNEL_CRISIS = "biomarker_nudges_crisis"
        private const val NOTIF_ID_BASE = 6100
    }
}

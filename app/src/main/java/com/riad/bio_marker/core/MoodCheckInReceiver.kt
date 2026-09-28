package com.riad.bio_marker.core

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles taps on the 3 emoji action buttons in a CHECK_IN nudge:
 *  - 😊 → MoodGroundTruth.Mood.GOOD
 *  - 😐 → MoodGroundTruth.Mood.NEUTRAL
 *  - 😔 → MoodGroundTruth.Mood.LOW
 *
 * Records the tap as ground truth, marks the CHECK_IN nudge as "completed"
 * for the day (so it doesn't fire again), and dismisses the notification.
 */
class MoodCheckInReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mood = when (intent.action) {
            ACTION_GOOD -> MoodGroundTruth.Mood.GOOD
            ACTION_NEUTRAL -> MoodGroundTruth.Mood.NEUTRAL
            ACTION_LOW -> MoodGroundTruth.Mood.LOW
            else -> return
        }
        ServiceLocator.moodGroundTruth.record(mood)
        // Treat a successful tap as "the user already did the check-in today",
        // so we don't keep nudging them.
        ServiceLocator.nudgeStore.markFired(NudgeStore.Type.CHECK_IN)

        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(notifId)
        }

        // Optionally: if user reports LOW we can immediately escalate.
        if (mood == MoodGroundTruth.Mood.LOW) {
            // Bypass cooldown by writing a CRISIS marker the engine respects.
            ServiceLocator.nudgeStore.requestCrisisFollowUp()
        }
    }

    companion object {
        const val ACTION_GOOD = "com.riad.bio_marker.MOOD_GOOD"
        const val ACTION_NEUTRAL = "com.riad.bio_marker.MOOD_NEUTRAL"
        const val ACTION_LOW = "com.riad.bio_marker.MOOD_LOW"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}


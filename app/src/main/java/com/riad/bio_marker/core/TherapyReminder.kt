package com.riad.bio_marker.core

import android.content.Context

/**
 * Minimal therapy-reminder hook used by [com.riad.bio_marker.ml.CrisisNotifier]
 * to pull the next session forward by 48 h on a crisis trigger.
 *
 * For Slice 4 this just stores a "next reminder" epoch in SharedPreferences so
 * the rest of the app can plug in (e.g. a calendar integration). It's a no-op
 * if no reminder has been scheduled.
 */
class TherapyReminder(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_therapy", Context.MODE_PRIVATE)

    var nextReminderMs: Long
        get() = prefs.getLong(KEY_NEXT, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT, value).apply()

    fun advanceBy48h(nowMs: Long = System.currentTimeMillis()) {
        val cur = nextReminderMs
        if (cur <= nowMs) return // nothing scheduled or already passed
        val advanced = (cur - 48L * 3600_000L).coerceAtLeast(nowMs + 60_000L)
        nextReminderMs = advanced
    }

    fun clear() = prefs.edit().clear().apply()

    companion object { private const val KEY_NEXT = "next_ms" }
}


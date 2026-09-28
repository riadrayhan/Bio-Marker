package com.riad.bio_marker.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Persistence for the gentle-nudge engine. Stores per-type "last fired"
 * timestamps so we can enforce cooldowns, and a global "quiet mode" toggle
 * the user can flip in Settings to silence all non-crisis nudges.
 */
class NudgeStore(context: Context) {

    enum class Type {
        // Original five
        ENCOURAGEMENT,
        ACTIVITY,
        SLEEP,
        BREATHING,
        SOCIAL,
        CHECK_IN,
        CRISIS_SUPPORT,

        // JITAI taxonomy (new)
        VALIDATION,            // pure acknowledgement of a positive signal
        MICRO_ACTION,          // 2-min, easy task framed afternoon-only
        PATTERN_REFLECTION,    // weekly, curious tone, references a real pattern
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_nudges", Context.MODE_PRIVATE)

    /** True = the user has muted gentle nudges. Crisis nudges still go through. */
    var quietMode: Boolean
        get() = prefs.getBoolean(K_QUIET, false)
        set(value) { prefs.edit().putBoolean(K_QUIET, value).apply() }

    fun lastFiredMs(type: Type): Long = prefs.getLong("last_${type.name}", 0L)

    fun markFired(type: Type, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong("last_${type.name}", nowMs)
            .putString("day_${type.name}", todayKey())
            .putString(K_LAST_DAY, todayKey())
            .putInt(K_TODAY_COUNT, todayCountInternal() + 1)
            .apply()
    }

    /** True if [type] has already been fired today (literal "once per day per type"). */
    fun firedTodayForType(type: Type): Boolean =
        prefs.getString("day_${type.name}", "") == todayKey()

    fun firedTodayCount(): Int =
        if (prefs.getString(K_LAST_DAY, "") == todayKey()) prefs.getInt(K_TODAY_COUNT, 0)
        else 0

    private fun todayCountInternal(): Int {
        val today = todayKey()
        return if (prefs.getString(K_LAST_DAY, "") == today) prefs.getInt(K_TODAY_COUNT, 0) else 0
    }

    private fun todayKey(): String = FORMAT.format(Calendar.getInstance().time)

    fun hoursSince(type: Type, nowMs: Long = System.currentTimeMillis()): Float {
        val last = lastFiredMs(type)
        if (last == 0L) return Float.MAX_VALUE
        return (nowMs - last) / 3_600_000f
    }

    /** Set by the mood-check-in receiver when the user picks "low". The next
     *  worker tick will pick up the flag and offer a crisis nudge regardless
     *  of the normal cooldown. */
    fun requestCrisisFollowUp() {
        prefs.edit().putBoolean(K_CRISIS_PENDING, true).apply()
    }

    fun consumeCrisisFollowUp(): Boolean {
        val v = prefs.getBoolean(K_CRISIS_PENDING, false)
        if (v) prefs.edit().remove(K_CRISIS_PENDING).apply()
        return v
    }

    companion object {
        private const val K_QUIET = "quiet"
        private const val K_LAST_DAY = "last_day"
        private const val K_TODAY_COUNT = "today_count"
        private const val K_CRISIS_PENDING = "crisis_pending"
        private val FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}


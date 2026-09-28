package com.riad.bio_marker.collectors

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/**
 * Computes daily usage features from [UsageStatsManager]:
 *  - total foreground time (minutes)
 *  - late-night usage (minutes after 22:00 and before 05:00)
 *  - phone unlocks today (USER_INTERACTION events as a proxy)
 *  - social-app foreground ratio
 *
 * The user must grant the special "Usage access" permission via
 * Settings → Apps → Special app access → Usage access. We don't request it
 * with the runtime permissions API — Android requires the user to flip it
 * manually. [hasPermission] reports the current state.
 */
class UsageStatsCollector(private val context: Context) {

    data class DayUsage(
        val totalForegroundMin: Int,
        val lateNightMin: Int,
        val unlockCount: Int,
        val socialAppRatio: Float,
        val perAppMinutes: Map<String, Int>,
        /** Individual foreground sessions (start/end per app) for the timeline. */
        val sessions: List<AppSession>,
    )

    /**
     * One foreground session of a single app: from MOVE_TO_FOREGROUND /
     * ACTIVITY_RESUMED until the matching PAUSED / BACKGROUND. Sessions
     * shorter than ~5 s are filtered out by the timeline writer.
     */
    data class AppSession(
        val packageName: String,
        val startMs: Long,
        val endMs: Long,
    ) { val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L) }

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Computes [DayUsage] for the calendar day containing [endMs] (default = now). */
    fun snapshot(endMs: Long = System.currentTimeMillis()): DayUsage? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = endMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return snapshotRange(cal.timeInMillis, endMs)
    }

    /**
     * Computes [DayUsage] for an arbitrary `[startMs, endMs]` range. Used by
     * the window summary so per-app minutes reflect the *exact* monitoring
     * window (1 h / 3 h / 24 h / 48 h) instead of just "today's calendar day".
     * For sub-day windows ending mid-day this is the only way per-category
     * minutes (social-media / video) come out non-zero, since the calendar-day
     * APP_USE events haven't been written yet by the nightly worker.
     */
    fun snapshotRange(startMs: Long, endMs: Long): DayUsage? {
        if (!hasPermission()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val events = usm.queryEvents(startMs, endMs)
        val ev = UsageEvents.Event()

        // Per-package fg session timing.
        val pkgFgStart = mutableMapOf<String, Long>()
        val perAppMs = mutableMapOf<String, Long>()
        val sessions = mutableListOf<AppSession>()
        var lateNightMs = 0L
        var unlockCount = 0

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    pkgFgStart[pkg] = ev.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val s = pkgFgStart.remove(pkg) ?: continue
                    val dur = ev.timeStamp - s
                    perAppMs[pkg] = (perAppMs[pkg] ?: 0L) + dur
                    lateNightMs += overlapWithLateNight(s, ev.timeStamp)
                    sessions += AppSession(pkg, s, ev.timeStamp)
                }
                UsageEvents.Event.USER_INTERACTION -> {
                    // Crude unlock proxy on devices that don't expose KEYGUARD events.
                }
                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> Unit
                23 /* KEYGUARD_HIDDEN, since API 28 */ -> unlockCount++
            }
        }
        // Close any still-open sessions at endMs.
        for ((pkg, s) in pkgFgStart) {
            val dur = endMs - s
            perAppMs[pkg] = (perAppMs[pkg] ?: 0L) + dur
            lateNightMs += overlapWithLateNight(s, endMs)
            sessions += AppSession(pkg, s, endMs)
        }

        val totalMs = perAppMs.values.sum()
        val socialMs = perAppMs.entries.filter { it.key in SOCIAL_PACKAGES }.sumOf { it.value }
        val socialRatio = if (totalMs > 0) socialMs.toFloat() / totalMs else 0f

        return DayUsage(
            totalForegroundMin = (totalMs / 60_000L).toInt(),
            lateNightMin = (lateNightMs / 60_000L).toInt(),
            unlockCount = unlockCount,
            socialAppRatio = socialRatio,
            perAppMinutes = perAppMs.mapValues { (it.value / 60_000L).toInt() },
            sessions = sessions.sortedBy { it.startMs },
        )
    }

    /** Overlap (ms) of a session with the late-night window 22:00–05:00 next day. */
    private fun overlapWithLateNight(startMs: Long, endMs: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = startMs }
        cal.set(Calendar.HOUR_OF_DAY, 22); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val nightStart = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, 7) // 05:00 next day
        val nightEnd = cal.timeInMillis
        val a = maxOf(startMs, nightStart)
        val b = minOf(endMs, nightEnd)
        var overlap = (b - a).coerceAtLeast(0L)
        // Also count the early-morning slice 00:00–05:00 of the SAME day.
        cal.timeInMillis = startMs
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val morningStart = cal.timeInMillis
        cal.add(Calendar.HOUR_OF_DAY, 5)
        val morningEnd = cal.timeInMillis
        overlap += (minOf(endMs, morningEnd) - maxOf(startMs, morningStart)).coerceAtLeast(0L)
        return overlap
    }

    companion object {
        /** Apps shorter than this are noise (locked screen flicker etc). */
        private const val MIN_SESSION_MS = 15_000L

        /**
         * Persists significant app sessions as APP_USE [com.riad.bio_marker.core.DailyEvent]s.
         * Idempotency: on first call for a given day, deletes pre-existing
         * APP_USE events for that day so re-running is safe (this collector
         * is the source of truth for app sessions).
         */
        fun writeAppSessionsToTimeline(
            sessions: List<AppSession>,
            dateKey: String,
        ) {
            val store = com.riad.bio_marker.core.ServiceLocator.dailyEventStore
            val consent = com.riad.bio_marker.core.ServiceLocator.consentStore
            if (!consent.isEnabled(com.riad.bio_marker.core.ConsentStore.Signal.APP_SESSIONS)) return
            // Drop existing APP_USE events for this day, keep everything else.
            val existing = store.load(dateKey).filter {
                it.type != com.riad.bio_marker.core.DailyEvent.Type.APP_USE
            }
            store.saveAll(dateKey, existing)
            for (s in sessions) {
                if (s.durationMs < MIN_SESSION_MS) continue
                val cat = com.riad.bio_marker.core.AppCategoryMap.categoryOf(s.packageName)
                val name = com.riad.bio_marker.core.AppCategoryMap.displayName(s.packageName)
                store.append(
                    com.riad.bio_marker.core.DailyEvent(
                        id = "",
                        type = com.riad.bio_marker.core.DailyEvent.Type.APP_USE,
                        startMs = s.startMs,
                        endMs = s.endMs,
                        source = "usage_stats",
                        metadata = mapOf(
                            "package" to s.packageName,
                            "app_name" to name,
                            "category" to cat.name,
                        ),
                    )
                )
            }
        }

        private val SOCIAL_PACKAGES = setOf(
            "com.whatsapp",
            "com.facebook.katana",
            "com.facebook.orca",        // Messenger
            "com.instagram.android",
            "org.telegram.messenger",
            "com.twitter.android",
            "com.imo.android.imoim",
            "com.snapchat.android",
            "com.discord",
            "com.viber.voip",
            "com.skype.raider",
        )
    }
}


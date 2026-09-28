package com.riad.bio_marker.core

import android.content.Context
import com.riad.bio_marker.R
import java.util.Calendar

/**
 * Scores how aligned the user's actual sleep schedule is with healthy
 * targets, using **device local time**. Healthy reference values are
 * widely-recommended adult norms (CDC / AASM):
 *
 *   - Wake-up window 05:30–07:30 = ideal (score 100)
 *   - Wake-up before 09:00       = OK   (60)
 *   - Wake-up after  09:00       = Late (30)
 *
 *   - Bedtime  21:30–23:00       = ideal (100)
 *   - Bedtime  23:00–24:00       = OK   (60)
 *   - Bedtime  after midnight    = Late (30)
 *
 *   - Total sleep 7–9 h = ideal, 6–7 = OK, <6 or >10 = poor.
 *
 * The final [score] is the average of the three sub-scores (0..100).
 * Empty inputs (`bedtimeMs`/`wakeMs` == null) drop out of the average.
 */
object RoutineAnalyzer {

    enum class Band { GOOD, OK, LATE, POOR, UNKNOWN }

    data class Result(
        val score: Int,            // 0..100 (overall routine quality)
        val wakeBand: Band,
        val bedBand: Band,
        val sleepBand: Band,
        val wakeLocal: String?,    // "HH:mm"
        val bedLocal: String?,     // "HH:mm"
        val sleepHours: Float,
        val feedback: String,      // one-line localized
    )

    fun analyze(
        ctx: Context,
        bedtimeMs: Long?,
        wakeMs: Long?,
        sleepHours: Float,
    ): Result {
        val (wakeBand, wakeLocal) = scoreWake(wakeMs)
        val (bedBand, bedLocal) = scoreBed(bedtimeMs)
        val sleepBand = scoreSleep(sleepHours)

        val parts = listOfNotNull(
            wakeBand.toScore(),
            bedBand.toScore(),
            sleepBand.toScore(),
        )
        val score = if (parts.isEmpty()) 0 else parts.average().toInt()

        val sb = StringBuilder()
        when (wakeBand) {
            Band.LATE -> sb.append(ctx.getString(R.string.routine_wake_late, wakeLocal ?: "?"))
            Band.OK -> sb.append(ctx.getString(R.string.routine_wake_ok, wakeLocal ?: "?"))
            Band.GOOD -> sb.append(ctx.getString(R.string.routine_wake_good, wakeLocal ?: "?"))
            else -> Unit
        }
        if (sb.isNotEmpty()) sb.append(' ')
        when (bedBand) {
            Band.LATE -> sb.append(ctx.getString(R.string.routine_bed_late, bedLocal ?: "?"))
            Band.OK -> sb.append(ctx.getString(R.string.routine_bed_ok, bedLocal ?: "?"))
            Band.GOOD -> sb.append(ctx.getString(R.string.routine_bed_good, bedLocal ?: "?"))
            else -> Unit
        }
        if (sb.isNotEmpty()) sb.append(' ')
        when (sleepBand) {
            Band.POOR -> sb.append(ctx.getString(R.string.routine_sleep_poor, sleepHours))
            Band.OK -> sb.append(ctx.getString(R.string.routine_sleep_ok, sleepHours))
            Band.GOOD -> sb.append(ctx.getString(R.string.routine_sleep_good, sleepHours))
            else -> Unit
        }
        val feedback = sb.toString().trim().ifEmpty {
            ctx.getString(R.string.routine_unknown)
        }

        return Result(
            score = score,
            wakeBand = wakeBand, bedBand = bedBand, sleepBand = sleepBand,
            wakeLocal = wakeLocal, bedLocal = bedLocal,
            sleepHours = sleepHours, feedback = feedback,
        )
    }

    private fun Band.toScore(): Int? = when (this) {
        Band.GOOD -> 100
        Band.OK -> 60
        Band.LATE -> 30
        Band.POOR -> 20
        Band.UNKNOWN -> null
    }

    private fun scoreWake(ms: Long?): Pair<Band, String?> {
        if (ms == null || ms == 0L) return Band.UNKNOWN to null
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val mins = h * 60 + m
        val band = when {
            mins in (5 * 60 + 30)..(7 * 60 + 30) -> Band.GOOD
            mins < 9 * 60 -> Band.OK
            else -> Band.LATE
        }
        return band to "%02d:%02d".format(h, m)
    }

    private fun scoreBed(ms: Long?): Pair<Band, String?> {
        if (ms == null || ms == 0L) return Band.UNKNOWN to null
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        // Treat 0..12 as "late" (24..36h) so 23:30 and 00:30 compare correctly.
        val mins = if (h < 12) (h + 24) * 60 + m else h * 60 + m
        val band = when {
            mins in (21 * 60 + 30)..(23 * 60) -> Band.GOOD
            mins in (23 * 60)..(24 * 60) -> Band.OK
            else -> Band.LATE
        }
        return band to "%02d:%02d".format(h, m)
    }

    private fun scoreSleep(hrs: Float): Band = when {
        hrs <= 0f -> Band.UNKNOWN
        hrs in 7f..9f -> Band.GOOD
        hrs in 6f..10f -> Band.OK
        hrs < 6f -> Band.POOR
        else -> Band.OK
    }
}


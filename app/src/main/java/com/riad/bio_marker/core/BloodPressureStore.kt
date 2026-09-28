package com.riad.bio_marker.core

import android.content.Context

/**
 * Persists the user's most recent **measured** vitals — heart-rate (BPM)
 * and blood-pressure (systolic / diastolic).
 *
 * Both are produced by [com.riad.bio_marker.core.PpgHeartRateMeasurer], which
 * runs a fingertip photoplethysmography pass over the back camera + torch
 * (the same technique used by Samsung Health / Google Fit / Instant Heart
 * Rate). HR comes from peak-detected pulse intervals; BP is a transparent
 * cuffless **estimate** derived from HR + HRV (RMSSD) + perfusion amplitude.
 *
 * Manual BP entry from a real cuff is also accepted via [save] — it
 * overwrites the PPG estimate and should be preferred when available.
 *
 * Stored as a simple SharedPreferences blob so the AI summary
 * ([com.riad.bio_marker.core.WindowSummaryBuilder]) can include the latest
 * vitals in its end-of-window report.
 */
class BloodPressureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_bp", Context.MODE_PRIVATE)

    enum class Source { PPG, MANUAL }

    data class Reading(
        val systolic: Int,
        val diastolic: Int,
        val tsMs: Long,
        val source: Source = Source.MANUAL,
    )

    data class HrReading(
        val bpm: Int,
        val tsMs: Long,
        val source: Source = Source.PPG,
    )

    /** Manual BP entry from a real cuff. Marked as [Source.MANUAL]. */
    fun save(systolic: Int, diastolic: Int) =
        save(systolic, diastolic, Source.MANUAL)

    /** Persist a BP reading with an explicit source (PPG or MANUAL). */
    fun save(systolic: Int, diastolic: Int, source: Source) {
        prefs.edit()
            .putInt(K_SYS, systolic)
            .putInt(K_DIA, diastolic)
            .putLong(K_TS, System.currentTimeMillis())
            .putString(K_SRC, source.name)
            .apply()
    }

    /** Persist a PPG-measured heart rate. */
    fun saveHr(bpm: Int, source: Source = Source.PPG) {
        prefs.edit()
            .putInt(K_HR, bpm)
            .putLong(K_HR_TS, System.currentTimeMillis())
            .putString(K_HR_SRC, source.name)
            .apply()
    }

    fun latest(): Reading? {
        if (!prefs.contains(K_SYS)) return null
        return Reading(
            systolic = prefs.getInt(K_SYS, 0),
            diastolic = prefs.getInt(K_DIA, 0),
            tsMs = prefs.getLong(K_TS, 0L),
            source = runCatching { Source.valueOf(prefs.getString(K_SRC, Source.MANUAL.name)!!) }
                .getOrDefault(Source.MANUAL),
        )
    }

    fun latestHr(): HrReading? {
        if (!prefs.contains(K_HR)) return null
        return HrReading(
            bpm = prefs.getInt(K_HR, 0),
            tsMs = prefs.getLong(K_HR_TS, 0L),
            source = runCatching { Source.valueOf(prefs.getString(K_HR_SRC, Source.PPG.name)!!) }
                .getOrDefault(Source.PPG),
        )
    }

    /** Returns the latest BP reading whose timestamp is in [start, end]. */
    fun readingInWindow(start: Long, end: Long): Reading? {
        val r = latest() ?: return null
        return if (r.tsMs in start..end) r else null
    }

    /** Returns the latest HR reading whose timestamp is in [start, end]. */
    fun hrInWindow(start: Long, end: Long): HrReading? {
        val r = latestHr() ?: return null
        return if (r.tsMs in start..end) r else null
    }

    companion object {
        private const val K_SYS = "sys"
        private const val K_DIA = "dia"
        private const val K_TS = "ts"
        private const val K_SRC = "src"
        private const val K_HR = "hr"
        private const val K_HR_TS = "hr_ts"
        private const val K_HR_SRC = "hr_src"
    }
}


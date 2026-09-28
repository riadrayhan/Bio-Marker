package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Persists the rolling 30-day daily feature window used by the LSTM (last 7
 * days × 15 features) and by [com.riad.bio_marker.monitoring.CrisisRules].
 *
 * One JSON file per day under <filesDir>/daily_features/daily-YYYY-MM-DD.json.
 * Files older than [RETENTION_DAYS] are deleted by [pruneOld].
 */
class DailyFeatureStore(context: Context) {

    private val dir = java.io.File(context.filesDir, "daily_features").apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 15-feature daily vector — order MUST match the LSTM model input. */
    data class Daily(
        val date: String,                 // yyyy-MM-dd
        val motionEnergyMean: Float = 0f,
        val motionEnergyStd: Float = 0f,
        val sampleEntropy: Float = 0f,
        val stepCount: Int = 0,
        val sleepDurationHrs: Float = 0f,
        val sleepIrregularityHrs: Float = 0f,
        val locationEntropy: Float = 0f,
        val screenTimeMin: Int = 0,
        val screenTimeDelta7d: Float = 0f,
        val lateNightUsageMin: Int = 0,
        val socialAppRatio: Float = 0f,
        val voiceF0Hz: Float = 0f,
        val voiceJitter: Float = 0f,
        val voiceEnergyMean: Float = 0f,
        val phoneUnlockCount: Int = 0,
        // -- Routine timestamps (added v1.1) --
        val bedtimeMs: Long = 0L,
        val wakeMs: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("date", date)
            put("motion_energy_mean", motionEnergyMean.toDouble())
            put("motion_energy_std", motionEnergyStd.toDouble())
            put("sample_entropy", sampleEntropy.toDouble())
            put("step_count", stepCount)
            put("sleep_duration_hrs", sleepDurationHrs.toDouble())
            put("sleep_irregularity_hrs", sleepIrregularityHrs.toDouble())
            put("location_entropy", locationEntropy.toDouble())
            put("screen_time_min", screenTimeMin)
            put("screen_time_delta7d", screenTimeDelta7d.toDouble())
            put("late_night_usage_min", lateNightUsageMin)
            put("social_app_ratio", socialAppRatio.toDouble())
            put("voice_f0_hz", voiceF0Hz.toDouble())
            put("voice_jitter", voiceJitter.toDouble())
            put("voice_energy_mean", voiceEnergyMean.toDouble())
            put("phone_unlock_count", phoneUnlockCount)
            if (bedtimeMs > 0L) put("bedtime_ms", bedtimeMs)
            if (wakeMs > 0L) put("wake_ms", wakeMs)
        }

        fun toFloatArray(): FloatArray = floatArrayOf(
            motionEnergyMean, motionEnergyStd, sampleEntropy, stepCount.toFloat(),
            sleepDurationHrs, sleepIrregularityHrs, locationEntropy, screenTimeMin.toFloat(),
            screenTimeDelta7d, lateNightUsageMin.toFloat(), socialAppRatio,
            voiceF0Hz, voiceJitter, voiceEnergyMean, phoneUnlockCount.toFloat(),
        )

        companion object {
            fun fromJson(o: JSONObject): Daily = Daily(
                date = o.optString("date"),
                motionEnergyMean = o.optDouble("motion_energy_mean").toFloat(),
                motionEnergyStd = o.optDouble("motion_energy_std").toFloat(),
                sampleEntropy = o.optDouble("sample_entropy").toFloat(),
                stepCount = o.optInt("step_count"),
                sleepDurationHrs = o.optDouble("sleep_duration_hrs").toFloat(),
                sleepIrregularityHrs = o.optDouble("sleep_irregularity_hrs").toFloat(),
                locationEntropy = o.optDouble("location_entropy").toFloat(),
                screenTimeMin = o.optInt("screen_time_min"),
                screenTimeDelta7d = o.optDouble("screen_time_delta7d").toFloat(),
                lateNightUsageMin = o.optInt("late_night_usage_min"),
                socialAppRatio = o.optDouble("social_app_ratio").toFloat(),
                voiceF0Hz = o.optDouble("voice_f0_hz").toFloat(),
                voiceJitter = o.optDouble("voice_jitter").toFloat(),
                voiceEnergyMean = o.optDouble("voice_energy_mean").toFloat(),
                phoneUnlockCount = o.optInt("phone_unlock_count"),
                bedtimeMs = o.optLong("bedtime_ms", 0L),
                wakeMs = o.optLong("wake_ms", 0L),
            )
        }
    }

    fun save(d: Daily) {
        val f = java.io.File(dir, "daily-${d.date}.json")
        f.writeText(d.toJson().toString())
    }

    fun load(date: String): Daily? {
        val f = java.io.File(dir, "daily-$date.json")
        if (!f.exists()) return null
        return try { Daily.fromJson(JSONObject(f.readText())) } catch (_: Exception) { null }
    }

    /** Oldest first; missing days are skipped. */
    fun lastN(n: Int): List<Daily> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
        val out = mutableListOf<Daily>()
        repeat(n) {
            val key = fmt.format(cal.time)
            load(key)?.let { out.add(it) }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    /** Returns last [n] days of dailies in chronological order, padding missing
     *  days with zero-vector Dailies — needed by the LSTM (fixed shape input). */
    fun lastNPadded(n: Int): List<Daily> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1))
        val out = mutableListOf<Daily>()
        repeat(n) {
            val key = fmt.format(cal.time)
            out.add(load(key) ?: Daily(date = key))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    fun pruneOld(keepDays: Int = RETENTION_DAYS) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun todayKey(): String = fmt.format(Date())

    companion object {
        const val RETENTION_DAYS = 30
    }
}


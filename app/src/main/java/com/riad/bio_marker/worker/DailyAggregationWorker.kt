package com.riad.bio_marker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.collectors.SleepEstimator
import com.riad.bio_marker.collectors.UsageStatsCollector
import com.riad.bio_marker.core.DailyFeatureStore
import com.riad.bio_marker.core.ServiceLocator
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/**
 * Daily aggregator: scans yesterday's JSONL, computes feature summary, writes
 * <filesDir>/aggregates/daily-YYYY-MM-DD.json, then assembles the 15-feature
 * daily vector for [DailyFeatureStore] (consumed by the LSTM and crisis rules).
 *
 * Raw JSONL older than [RAW_RETENTION_DAYS] is pruned afterwards (the
 * RetentionWorker also enforces this independently).
 */
class DailyAggregationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val logger = ServiceLocator.sampleLogger
            val outDir = File(applicationContext.filesDir, "aggregates").apply { mkdirs() }

            for (file in logger.listFiles()) {
                val date = file.nameWithoutExtension.removePrefix("samples-")
                val agg = aggregate(file)
                val outFile = File(outDir, "daily-$date.json")
                FileWriter(outFile).use { it.write(agg.toString(2)) }
            }

            // Build today's 15-feature daily vector and persist it.
            persistTodayDaily()

            logger.deleteOlderThan(RAW_RETENTION_DAYS)
            ServiceLocator.dailyFeatureStore.pruneOld()
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    private fun persistTodayDaily() {
        val store = ServiceLocator.dailyFeatureStore
        val today = store.todayKey()

        // --- Motion / steps from MetricsBus ---
        val s = ServiceLocator.metricsBus.state.value
        val n = s.motionSamplesToday
        val motionMean = if (n > 0) (s.motionEnergySumToday / n).toFloat() else 0f
        // True intra-day std from running sum + sumSq (not the historical baseline).
        val motionStd = if (n > 1) {
            val mean = s.motionEnergySumToday / n
            val variance = (s.motionEnergySumSqToday / n) - (mean * mean)
            kotlin.math.sqrt(variance.coerceAtLeast(0.0)).toFloat()
        } else 0f

        // --- Voice (sparse: latest cached snapshot) ---
        val voice = ServiceLocator.voiceCache.load()
        val voiceEnergy = voice?.energyMean ?: 0f
        // Use the real autocorrelation-based F0 + jitter when present, fall back
        // to 0 (the LSTM pads zeros for missing modalities, which is fine).
        val voiceF0 = voice?.f0Hz ?: 0f
        val voiceJitter = voice?.jitter ?: 0f

        // --- Usage stats (optional) ---
        val usage = try { UsageStatsCollector(applicationContext).snapshot() } catch (_: Throwable) { null }

        // --- Screen-time delta vs 7-day mean ---
        val past7 = store.lastN(7)
        val past7Mean = past7.map { it.screenTimeMin }.let {
            if (it.isEmpty()) 0f else it.average().toFloat()
        }
        val todayScreen = usage?.totalForegroundMin ?: 0
        val screenDelta = if (past7Mean > 0f) todayScreen - past7Mean else 0f

        // --- Sleep ---
        val sleep = try { SleepEstimator(applicationContext).estimateForLastNight() }
        catch (_: Throwable) { SleepEstimator.Estimate(0f, null, null) }
        val sleepIrregularity = past7
            .map { it.sleepDurationHrs }
            .filter { it > 0f }
            .let { hrs ->
                if (hrs.size < 2) 0f
                else {
                    val m = hrs.average()
                    kotlin.math.sqrt(hrs.sumOf { (it - m) * (it - m) } / hrs.size).toFloat()
                }
            }

        // --- Location entropy (now actually wired to the live collector) ---
        val locEntropy = ServiceLocator.activeLocationCollector?.entropy() ?: 0f

        // --- Sample entropy of motion energies (downsampled from JSONL) ---
        val samp = sampleEntropyFromTodayJsonl()

        val daily = DailyFeatureStore.Daily(
            date = today,
            motionEnergyMean = motionMean,
            motionEnergyStd = motionStd,
            sampleEntropy = samp,
            stepCount = s.stepsToday,
            sleepDurationHrs = sleep.durationHrs,
            sleepIrregularityHrs = sleepIrregularity,
            locationEntropy = locEntropy,
            screenTimeMin = todayScreen,
            screenTimeDelta7d = screenDelta,
            lateNightUsageMin = usage?.lateNightMin ?: 0,
            socialAppRatio = usage?.socialAppRatio ?: 0f,
            voiceF0Hz = voiceF0,
            voiceJitter = voiceJitter,
            voiceEnergyMean = voiceEnergy,
            phoneUnlockCount = usage?.unlockCount ?: 0,
            bedtimeMs = sleep.bedtimeMs ?: 0L,
            wakeMs = sleep.wakeMs ?: 0L,
        )
        store.save(daily)
    }

    /**
     * Sample entropy (m=2, r=0.2σ) on today's motion-energy stream, downsampled
     * to ≤ 500 points. O(N²) but N ≤ 500 → trivial cost.
     * Returns 0f if not enough data.
     */
    private fun sampleEntropyFromTodayJsonl(): Float {
        val today = ServiceLocator.sampleLogger.todayFile() ?: return 0f
        val all = mutableListOf<Float>()
        try {
            today.forEachLine { line ->
                val o = try { JSONObject(line) } catch (_: Exception) { return@forEachLine }
                if (o.optString("k") == "motion") {
                    all += o.optDouble("e", 0.0).toFloat()
                }
            }
        } catch (_: Throwable) { return 0f }
        if (all.size < 30) return 0f
        // Downsample
        val target = 500
        val data = if (all.size > target) {
            val step = all.size / target
            (0 until target).map { all[it * step] }.toFloatArray()
        } else all.toFloatArray()

        val mean = data.average()
        val sd = kotlin.math.sqrt(data.sumOf { (it - mean) * (it - mean) } / data.size)
        if (sd <= 0.0) return 0f
        val r = (0.2 * sd).toFloat()
        val m = 2

        fun count(template: Int): Int {
            var c = 0
            val end = data.size - template
            for (i in 0 until end) {
                for (j in i + 1 until end) {
                    var ok = true
                    for (k in 0 until template) {
                        if (kotlin.math.abs(data[i + k] - data[j + k]) > r) { ok = false; break }
                    }
                    if (ok) c++
                }
            }
            return c
        }
        val a = count(m + 1)
        val b = count(m)
        if (b == 0 || a == 0) return 0f
        return (-kotlin.math.ln(a.toDouble() / b.toDouble())).toFloat()
            .coerceIn(0f, 5f)
    }

    private fun aggregate(file: File): JSONObject {
        var motionCount = 0
        var motionEnergySum = 0.0
        var motionEnergyMax = 0.0
        var keyCount = 0
        var latencySum = 0L
        var backspaceCount = 0

        file.forEachLine { line ->
            try {
                val o = JSONObject(line)
                when (o.optString("k")) {
                    "motion" -> {
                        motionCount++
                        val e = o.optDouble("e", 0.0)
                        motionEnergySum += e
                        if (e > motionEnergyMax) motionEnergyMax = e
                    }
                    "key" -> {
                        keyCount++
                        latencySum += o.optLong("lat", 0L)
                        if (o.optBoolean("bs")) backspaceCount++
                    }
                }
            } catch (_: Exception) { /* skip bad line */ }
        }

        return JSONObject().apply {
            put("source_file", file.name)
            put("motion_samples", motionCount)
            put("motion_energy_mean", if (motionCount > 0) motionEnergySum / motionCount else 0.0)
            put("motion_energy_max", motionEnergyMax)
            put("keystrokes", keyCount)
            put("avg_inter_key_latency_ms", if (keyCount > 0) latencySum / keyCount else 0L)
            put("backspace_rate", if (keyCount > 0) backspaceCount.toDouble() / keyCount else 0.0)
        }
    }

    companion object {
        private const val RAW_RETENTION_DAYS = 7
    }
}

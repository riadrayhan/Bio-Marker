package com.riad.bio_marker.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Best-effort heart-rate sampler. Most phones do **not** have a heart-rate
 * sensor — only Wear OS watches and a handful of legacy Samsung devices
 * (Galaxy S5–S10) expose [Sensor.TYPE_HEART_RATE].
 *
 * On phones without the sensor [start] returns false and the collector is
 * a no-op. The downstream [com.riad.bio_marker.core.WindowSummaryStore]
 * stores `null` for heart-rate fields, and the AI prompt simply omits
 * vitals from the analysis.
 *
 * Permission: [Manifest.permission.BODY_SENSORS] is dangerous and must be
 * requested at runtime. We never persist raw waveforms — only running
 * min / max / average BPM for the active monitoring window.
 *
 * Blood pressure is not exposed by any standard Android sensor API, so we
 * cannot include it in this v1; integration with Health Connect could
 * surface BP from a paired device in the future.
 */
class HeartRateCollector(private val context: Context) : SensorEventListener {

    private var sm: SensorManager? = null
    private var sensor: Sensor? = null

    @Volatile private var sumBpm: Float = 0f
    @Volatile private var nSamples: Int = 0
    @Volatile private var minBpm: Float = Float.MAX_VALUE
    @Volatile private var maxBpm: Float = 0f

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BODY_SENSORS
    ) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "BODY_SENSORS not granted — heart-rate collection disabled")
            return false
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false
        val s = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (s == null) {
            Log.w(TAG, "TYPE_HEART_RATE unavailable on this device")
            return false
        }
        sm = manager
        sensor = s
        return manager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL).also {
            Log.i(TAG, "registerListener(heartRate) -> $it")
        }
    }

    fun stop() {
        try { sm?.unregisterListener(this) } catch (_: Throwable) {}
        sm = null
        sensor = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val v = event?.values?.firstOrNull() ?: return
        if (v <= 30f || v > 220f) return       // implausible — drop
        sumBpm += v
        nSamples += 1
        if (v < minBpm) minBpm = v
        if (v > maxBpm) maxBpm = v
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Snapshot since [start] / last [reset]. Null fields when no samples. */
    data class Stats(val avg: Float?, val min: Float?, val max: Float?, val n: Int)

    fun stats(): Stats {
        if (nSamples == 0) return Stats(null, null, null, 0)
        return Stats(
            avg = sumBpm / nSamples,
            min = minBpm,
            max = maxBpm,
            n = nSamples,
        )
    }

    fun reset() {
        sumBpm = 0f
        nSamples = 0
        minBpm = Float.MAX_VALUE
        maxBpm = 0f
    }

    companion object {
        private const val TAG = "HeartRateCollector"
    }
}


package com.riad.bio_marker.collectors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import com.riad.bio_marker.core.MetricsBus
import com.riad.bio_marker.core.SampleLogger
import com.riad.bio_marker.ml.AnomalyDetector
import com.riad.bio_marker.ml.FeatureSpec
import com.riad.bio_marker.ml.UserBaseline
import kotlin.math.sqrt

/**
 * Computes per-sample motion energy = ||a|| - g  (linear acceleration magnitude minus gravity).
 * Pushes a downsampled stream to the [MetricsBus] for the UI, writes every sample to
 * [SampleLogger] for offline aggregation, and runs a real-time [AnomalyDetector]
 * personalised against the user's own [UserBaseline].
 */
class MotionCollector(
    private val bus: MetricsBus,
    private val logger: SampleLogger,
    baseline: UserBaseline,
) : SensorEventListener {

    private val anomaly = AnomalyDetector(baseline, FeatureSpec.NAMES[0])

    private var lastLogMs: Long = 0
    private var lastUiMs: Long = 0

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val energy = (magnitude - GRAVITY).coerceAtLeast(0f)

        val nowMs = System.currentTimeMillis()

        // Real-time, personalised anomaly check (always — no rate limit, baseline is cheap).
        val isAnomalous = anomaly.process(energy)

        // Persist at ~10Hz (every 100ms) — keeps file size manageable.
        if (nowMs - lastLogMs >= 100) {
            logger.appendMotion(nowMs, energy, x, y, z)
            lastLogMs = nowMs
        }

        // Push to UI at ~5Hz.
        if (nowMs - lastUiMs >= 200) {
            bus.onMotionSample(energy, isAnomalous)
            lastUiMs = nowMs
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val GRAVITY = 9.81f
    }
}

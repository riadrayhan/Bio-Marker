package com.riad.bio_marker.collectors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import com.riad.bio_marker.core.MetricsBus

/**
 * Reads Android's [Sensor.TYPE_STEP_COUNTER] (cumulative steps since boot)
 * and publishes the *delta since this collector started* to [MetricsBus]
 * as `stepsToday`. Reset happens when [MetricsBus.resetDaily] is called.
 */
class StepCollector(private val bus: MetricsBus) : SensorEventListener {

    private var firstReading: Long = -1L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val total = event.values.firstOrNull()?.toLong() ?: return
        if (firstReading < 0L) firstReading = total
        val delta = (total - firstReading).coerceAtLeast(0L).toInt()
        bus.onStepCount(delta)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}


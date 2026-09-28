package com.riad.bio_marker.collectors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.riad.bio_marker.core.ConsentStore
import com.riad.bio_marker.core.DailyEvent
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.recordEvent

/**
 * Closes a "walking bout" when there has been no step-detector firing for
 * [GAP_MS] milliseconds. The earliest step in the current bout becomes the
 * event start; the last step becomes the event end.
 *
 * Why TYPE_STEP_DETECTOR (per-step) instead of TYPE_STEP_COUNTER (running
 * total)? Detector gives us individual timestamps which is exactly what
 * "did the user walk continuously?" needs. Counter only gives a monotonic
 * total since boot.
 *
 * Falls back gracefully if the device has no step detector — we just don't
 * emit walking events for that user (steps daily total is still tracked
 * by [StepCollector]).
 *
 * Threading: SensorEventListener callbacks come on the main looper; we keep
 * everything synchronous + fast (no I/O on the hot path) and post the timeout
 * on a [Handler] tied to the main looper.
 */
class WalkBoutDetector(
    private val sensorManager: SensorManager,
) : SensorEventListener {

    @Volatile private var firstStepMs: Long = 0L
    @Volatile private var lastStepMs: Long = 0L
    @Volatile private var stepsInBout: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { closeBoutIfStale() }

    fun start(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (sensor == null) {
            Log.w(TAG, "TYPE_STEP_DETECTOR unavailable — walking bouts disabled")
            return false
        }
        val ok = sensorManager.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_NORMAL
        )
        Log.i(TAG, "registerListener(stepDetector) -> $ok")
        return ok
    }

    fun stop() {
        try { sensorManager.unregisterListener(this) } catch (_: Throwable) {}
        handler.removeCallbacks(timeoutRunnable)
        // Flush any in-progress bout before shutdown.
        closeBoutIfAny(force = true)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_DETECTOR) return
        val now = System.currentTimeMillis()
        if (firstStepMs == 0L) firstStepMs = now
        lastStepMs = now
        stepsInBout += event.values.firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
        // Re-arm the gap timer.
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, GAP_MS)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun closeBoutIfStale() {
        val now = System.currentTimeMillis()
        if (lastStepMs != 0L && (now - lastStepMs) >= GAP_MS) {
            closeBoutIfAny(force = false)
        }
    }

    private fun closeBoutIfAny(force: Boolean) {
        val s = firstStepMs
        val e = lastStepMs
        val n = stepsInBout
        if (s == 0L || e == 0L) return
        val durMs = e - s
        if (force || durMs >= MIN_BOUT_MS) {
            ServiceLocator.recordEvent(
                type = DailyEvent.Type.WALK,
                startMs = s,
                endMs = e,
                source = "step_detector",
                metadata = mapOf(
                    "steps" to n.toString(),
                    "duration_sec" to (durMs / 1000L).toString(),
                ),
                requiredSignal = ConsentStore.Signal.WALK_BOUTS,
            )
        }
        firstStepMs = 0L
        lastStepMs = 0L
        stepsInBout = 0
    }

    companion object {
        private const val TAG = "WalkBoutDetector"
        /** Gap of inactivity that closes a bout (90 s ≈ standing still / red light). */
        private const val GAP_MS = 90_000L
        /** Drop bouts shorter than 60 s (likely a few stray steps in the kitchen). */
        private const val MIN_BOUT_MS = 60_000L
    }
}


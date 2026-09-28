package com.riad.bio_marker.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Camera-based **photoplethysmography (PPG)** heart-rate measurer — the same
 * technique used by Samsung Health, Google Fit, Instant Heart Rate, etc.
 *
 * How it works:
 *  1. The user covers the back camera lens (and torch) with their fingertip.
 *  2. We open the back camera at low resolution (160×120) and the torch.
 *  3. For every analysed frame we average the **Y luminance** plane — when
 *     blood pulses through the fingertip, the amount of light reaching the
 *     sensor changes slightly, producing a periodic signal at the heart rate.
 *  4. We detrend by subtracting a moving average and run a simple peak
 *     detector. BPM = `60_000 / mean_inter_peak_interval_ms`.
 *  5. After ~25 seconds of data we report a stable BPM, or surface an error
 *     if the signal was too noisy (finger off the lens, torch unavailable).
 *
 * Permission: [Manifest.permission.CAMERA] must be granted by the caller
 * before [start] — we no-op + return false if it isn't.
 */
class PpgHeartRateMeasurer(private val context: Context) {

    interface Listener {
        /** Progress in 0..1 (fraction of MEASURE_DURATION_MS captured). */
        fun onProgress(fraction: Float, livePulse: Float)
        /** Called once with the final BPM, or null if we couldn't lock on. */
        fun onResult(bpm: Float?, reason: String?)
        /**
         * Optional: called once at the end of a successful measurement with
         * a transparent **estimate** of systolic / diastolic blood pressure
         * derived from the same PPG capture (HR + HRV + waveform amplitude).
         * This is **not medical-grade** — it's a guidance estimate, just
         * like cuffless wrist-watch BP. Returns null if confidence is low.
         */
        fun onBpEstimate(sys: Int?, dia: Int?, reason: String?) {}
        /**
         * Optional live state of the per-frame finger-on-lens detector.
         *  - `detected = false` → measurement is **paused / not started**
         *    (waiting for a fingertip + torch over the back lens). Any
         *    samples already collected are discarded, so the 25-second
         *    countdown will restart from 0 once the finger comes back.
         *  - `detected = true`  → samples are being collected and the
         *    countdown advances normally.
         */
        fun onFingerState(detected: Boolean) {}
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var startMs: Long = 0L
    @Volatile private var stopped: Boolean = false
    /** Sentinel: 0L means we have not yet seen the finger (countdown paused). */
    @Volatile private var measuring: Boolean = false
    /** Consecutive bad-frame tolerance to ignore one-off chroma glitches. */
    private var badFrameStreak: Int = 0
    private var lastReportedDetected: Boolean? = null

    // Raw (ts, y) samples — capped at MAX_SAMPLES.
    private val tsList = ArrayList<Long>(2048)
    private val yList = ArrayList<Float>(2048)
    // Per-frame mean Cb (U) and Cr (V) chroma — used to verify the centre
    // patch is actually a fingertip pressed against the lens with torch on
    // (which makes the patch red-dominated). These are NOT used for BPM.
    private val uMeanList = ArrayList<Float>(2048)
    private val vMeanList = ArrayList<Float>(2048)

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts the camera + torch and begins collecting PPG samples.
     * Returns false immediately if CAMERA permission is missing.
     */
    fun start(
        owner: LifecycleOwner,
        listener: Listener,
        previewView: PreviewView? = null,
    ): Boolean {
        if (!hasPermission()) return false
        stopped = false
        measuring = false
        startMs = 0L
        badFrameStreak = 0
        lastReportedDetected = null
        tsList.clear(); yList.clear(); uMeanList.clear(); vMeanList.clear()

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            val cp = try { future.get() } catch (t: Throwable) {
                Log.e(TAG, "ProcessCameraProvider failed", t)
                listener.onResult(null, "camera unavailable")
                return@addListener
            }
            provider = cp
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor) { image -> processFrame(image, listener) }

            // Optional live preview so the user actually SEES the camera
            // feed (their finger covering the lens) while measurement runs.
            val preview = previewView?.let {
                Preview.Builder().build().apply {
                    setSurfaceProvider(it.surfaceProvider)
                }
            }

            try {
                cp.unbindAll()
                val useCases = if (preview != null) arrayOf(preview, analysis) else arrayOf(analysis)
                val camera = cp.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases,
                )
                if (camera.cameraInfo.hasFlashUnit()) {
                    camera.cameraControl.enableTorch(true)
                } else {
                    Log.w(TAG, "no flash unit — PPG will be very noisy")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "bindToLifecycle failed", t)
                listener.onResult(null, "camera bind failed")
            }
        }, ContextCompat.getMainExecutor(context))
        return true
    }

    fun stop() {
        stopped = true
        try { provider?.unbindAll() } catch (_: Throwable) {}
        provider = null
    }

    /**
     * Average a chroma plane (U or V) over a small centre patch.
     * Returns -1f if the plane buffer is empty or the patch falls outside
     * the buffer bounds.
     *
     * In YUV_420_888 the chroma planes are sub-sampled 2× in both
     * dimensions, so we halve the patch coordinates before reading.
     */
    private fun chromaMean(
        plane: androidx.camera.core.ImageProxy.PlaneProxy,
        cxFull: Int, cyFull: Int, wFull: Int, hFull: Int,
    ): Float {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
        val cx = cxFull / 2; val cy = cyFull / 2
        val w = wFull / 2;   val h = hFull / 2
        val r = 15
        var sum = 0L; var n = 0
        for (y in (cy - r) until (cy + r)) {
            if (y !in 0 until h) continue
            for (x in (cx - r) until (cx + r)) {
                if (x !in 0 until w) continue
                val idx = y * rowStride + x * pixelStride
                if (idx in bytes.indices) {
                    sum += (bytes[idx].toInt() and 0xFF)
                    n++
                }
            }
        }
        return if (n > 0) sum.toFloat() / n else -1f
    }

    private fun processFrame(image: ImageProxy, listener: Listener) {
        try {
            if (stopped) return
            val planes = image.planes
            val plane = planes.firstOrNull() ?: return
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            // Sample a 60×60 patch in the centre — that's where the fingertip
            // covers the lens — and average the Y plane.
            val cx = w / 2; val cy = h / 2
            val r = 30
            var sum = 0L; var n = 0
            val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            for (y in (cy - r) until (cy + r)) {
                if (y !in 0 until h) continue
                for (x in (cx - r) until (cx + r) step 2) {
                    if (x !in 0 until w) continue
                    val idx = y * rowStride + x * pixelStride
                    if (idx in bytes.indices) {
                        sum += (bytes[idx].toInt() and 0xFF)
                        n++
                    }
                }
            }
            if (n == 0) return

            // Also sample the U (Cb) and V (Cr) chroma planes from the same
            // centre region. With a fingertip pressed against the lens +
            // torch on, light bleeds through tissue → patch is dominated by
            // RED, so V (Cr) ≫ U (Cb). Without a finger, the chroma is
            // ~neutral. This is the strongest "is there actually a finger"
            // gate we have.
            var uMean = -1f; var vMean = -1f
            if (planes.size >= 3) {
                uMean = chromaMean(planes[1], cx, cy, w, h)
                vMean = chromaMean(planes[2], cx, cy, w, h)
            }

            val ts = System.currentTimeMillis()
            val v = sum.toFloat() / n

            // ---- Per-frame finger-on-lens gate ----
            //
            // Live version of the chroma + luminance gates used in
            // computeBpm(). With a real fingertip pressed against the back
            // lens AND the torch on, the centre patch is RED-dominated
            // (V ≫ U) and Y luminance lands roughly in [70..240]. Any
            // frame failing these is treated as "finger not on lens".
            // We tolerate up to ~10 consecutive bad frames (~0.3 s) to
            // ride out one-off chroma glitches; beyond that we discard
            // every collected sample and pause the countdown so that the
            // 25-second timer restarts from 0 the next time the finger
            // comes back.
            val fingerOk = v in 70f..240f &&
                (uMean < 0f || vMean < 0f || (vMean - uMean) >= 25f)

            if (!fingerOk) {
                badFrameStreak++
                if (badFrameStreak >= 10 || !measuring) {
                    // Drop everything and go back to "waiting for finger".
                    synchronized(yList) {
                        tsList.clear(); yList.clear()
                        uMeanList.clear(); vMeanList.clear()
                    }
                    measuring = false
                    startMs = 0L
                    if (lastReportedDetected != false) {
                        lastReportedDetected = false
                        listener.onFingerState(false)
                    }
                    listener.onProgress(0f, 0f)
                }
                return
            }

            // Finger looks good this frame.
            badFrameStreak = 0
            if (!measuring) {
                measuring = true
                startMs = ts
            }
            if (lastReportedDetected != true) {
                lastReportedDetected = true
                listener.onFingerState(true)
            }

            synchronized(yList) {
                tsList += ts
                yList += v
                uMeanList += uMean
                vMeanList += vMean
                if (yList.size > MAX_SAMPLES) {
                    tsList.removeAt(0); yList.removeAt(0)
                    uMeanList.removeAt(0); vMeanList.removeAt(0)
                }
            }

            val elapsed = ts - startMs
            val frac = (elapsed.toFloat() / MEASURE_DURATION_MS).coerceIn(0f, 1f)
            // Once we have at least 6 s of data, surface a live preview pulse.
            val live = if (yList.size >= 200) instantBpm() ?: 0f else 0f
            listener.onProgress(frac, live)

            if (elapsed >= MEASURE_DURATION_MS) {
                stopped = true
                stop()
                val (bpm, reason) = computeBpm()
                listener.onResult(bpm, reason)
                if (bpm != null) {
                    val (sys, dia, bpReason) = computeBpEstimate(bpm)
                    listener.onBpEstimate(sys, dia, bpReason)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun instantBpm(): Float? {
        val (bpm, _) = computeBpm(window = 80)
        return bpm
    }

    /**
     * Detrend (subtract moving average) → find peaks (local max above 0) →
     * average inter-peak interval → BPM. Heuristic but robust enough for
     * fingertip PPG with the torch on.
     */
    private fun computeBpm(window: Int = -1): Pair<Float?, String?> {
        synchronized(yList) {
            val data = if (window > 0 && yList.size > window)
                yList.subList(yList.size - window, yList.size).toFloatArray()
            else yList.toFloatArray()
            val ts = if (window > 0 && tsList.size > window)
                tsList.subList(tsList.size - window, tsList.size).toLongArray()
            else tsList.toLongArray()

            if (data.size < 60) return null to "not enough samples"

            // ---- Gate 0: STRONGEST — fingertip-over-torch redness check ----
            //
            // YUV chroma is the most discriminative feature we have. With a
            // real finger covering the back lens AND torch on, light bleeds
            // through tissue → centre patch is RED-dominated, so the Cr (V)
            // chroma is much higher than Cb (U). Empirically:
            //   - Finger on lens + torch: V ≈ 160..200, U ≈ 90..115,
            //     V - U ≈ 50..100.
            //   - No finger (any scene): V - U typically ≤ 15.
            // We require |V - U| ≥ 30 averaged across the full capture. If
            // it isn't, refuse to give a BPM. This eliminates the "I just
            // pressed Start without my finger" fake reading.
            val validUv = uMeanList.indices.count {
                uMeanList[it] >= 0f && vMeanList[it] >= 0f
            }
            if (validUv >= 30) {
                var uSum = 0f; var vSum = 0f; var k = 0
                for (i in uMeanList.indices) {
                    if (uMeanList[i] >= 0f && vMeanList[i] >= 0f) {
                        uSum += uMeanList[i]; vSum += vMeanList[i]; k++
                    }
                }
                if (k > 0) {
                    val uAvg = uSum / k
                    val vAvg = vSum / k
                    if (vAvg - uAvg < 30f) {
                        return null to
                            "Finger not detected. Cover the back lens AND the flash with your fingertip."
                    }
                }
            }

            // ---- Gate 1: finger must actually be covering lens + torch ----
            //
            // With a fingertip pressed against the back camera and the torch
            // on, the centre patch is brightly lit by red light bleeding
            // through the finger → mean Y luminance lands roughly in
            // [80..235]. With NO finger, the torch shines into the room and
            // the camera sees a normal scene → mean Y is much lower (or
            // dramatically higher if pointed at a bright surface). Either
            // way we refuse to give a fake reading.
            val mean = data.average().toFloat()
            if (mean < 70f)  return null to "Finger not detected. Cover the back lens AND flash with your fingertip."
            if (mean > 240f) return null to "Lens too bright. Cover the lens fully with your fingertip."

            // Gate 2: signal must actually vary (a covered-but-dead frame
            // would have constant Y).
            val absDev = data.sumOf { abs(it - mean).toDouble() }.toFloat() / data.size
            if (absDev < 0.6f) return null to "Finger not detected. Cover the back lens AND flash with your fingertip."

            // Gate 3: signal must NOT be wildly unstable (random scene
            // movement without a finger gives huge swings).
            if (absDev > 25f) return null to "Too much movement. Hold the phone steady with your fingertip on the lens."

            // Moving-average detrend (window = ~1.2 s). At ~30 fps that's 36
            // samples. We auto-scale to the data we have.
            val maW = (data.size / 8).coerceIn(8, 48)
            val detrended = FloatArray(data.size)
            for (i in data.indices) {
                val lo = maxOf(0, i - maW); val hi = minOf(data.size - 1, i + maW)
                var s = 0f; for (j in lo..hi) s += data[j]
                val ma = s / (hi - lo + 1)
                detrended[i] = data[i] - ma
            }

            // Peak detection — local maxima above 0 with min spacing 350 ms
            // (≈ 170 BPM ceiling, anything faster is noise).
            val peakTs = ArrayList<Long>()
            for (i in 1 until detrended.size - 1) {
                if (detrended[i] > 0f &&
                    detrended[i] > detrended[i - 1] &&
                    detrended[i] >= detrended[i + 1]) {
                    val t = ts[i]
                    if (peakTs.isEmpty() || t - peakTs.last() >= 350) {
                        peakTs += t
                    }
                }
            }
            if (peakTs.size < 4) return null to "Couldn't detect a pulse. Cover the back lens AND flash with your fingertip and hold still."
            val intervals = peakTs.zipWithNext { a, b -> (b - a).toFloat() }
            // Drop the most extreme intervals (1 high + 1 low) to dampen
            // noise from finger movement.
            val sorted = intervals.sorted()
            val trimmed = if (sorted.size > 6)
                sorted.subList(1, sorted.size - 1) else sorted
            val meanMs = trimmed.average().toFloat()
            if (meanMs <= 0f) return null to "No pulse signal. Cover the back lens AND flash with your fingertip."

            // ---- Gate 4: pulse must be REGULAR ----
            //
            // A real heartbeat produces inter-peak intervals with low
            // variability (CV ~5..15 % at rest). Random scene noise without
            // a finger produces "peaks" with much higher CV. Reject when
            // CV > 0.30 — that's well above any plausible arrhythmia and
            // is the clearest signature of a non-finger capture.
            val variance = trimmed.sumOf { val d = (it - meanMs).toDouble(); d * d } /
                trimmed.size
            val stdMs = kotlin.math.sqrt(variance).toFloat()
            val cv = if (meanMs > 0f) stdMs / meanMs else 1f
            if (cv > 0.30f)
                return null to "Couldn't detect a real pulse. Cover the back lens AND flash with your fingertip and hold still."

            val bpm = 60_000f / meanMs
            if (bpm !in 35f..200f) return null to "Implausible reading — try again with your fingertip on the lens."
            return bpm to null
        }
    }

    /**
     * Transparent blood-pressure **estimate** from the same PPG capture.
     *
     * We extract three features from the collected samples:
     *  - HR (already known)
     *  - HRV proxy (RMSSD of R-R intervals, ms)
     *  - Pulse-amplitude / DC ratio (perfusion). Lower amplitude + higher
     *    HR + lower HRV is loosely associated with elevated BP.
     *
     * We then apply a simple, published-heuristic linear combination
     * (clamped to plausible ranges). This is **NOT medical-grade**;
     * cuffless BP from PPG alone is an approximation. The UI labels it
     * as an estimate and recommends a real cuff reading for any concern.
     */
    private fun computeBpEstimate(bpm: Float): Triple<Int?, Int?, String?> {
        synchronized(yList) {
            if (yList.size < 200) return Triple(null, null, "not enough samples")
            val data = yList.toFloatArray()
            val ts = tsList.toLongArray()

            // Re-derive peaks (same logic as computeBpm) so we get peak
            // timestamps for HRV calculation.
            val maW = (data.size / 8).coerceIn(8, 48)
            val detrended = FloatArray(data.size)
            for (i in data.indices) {
                val lo = maxOf(0, i - maW); val hi = minOf(data.size - 1, i + maW)
                var s = 0f; for (j in lo..hi) s += data[j]
                detrended[i] = data[i] - s / (hi - lo + 1)
            }
            val peakIdx = ArrayList<Int>()
            for (i in 1 until detrended.size - 1) {
                if (detrended[i] > 0f &&
                    detrended[i] > detrended[i - 1] &&
                    detrended[i] >= detrended[i + 1]) {
                    if (peakIdx.isEmpty() || ts[i] - ts[peakIdx.last()] >= 350) {
                        peakIdx += i
                    }
                }
            }
            if (peakIdx.size < 5) return Triple(null, null, "low BP-signal confidence")

            // RMSSD over inter-peak intervals (ms).
            val rr = peakIdx.zipWithNext { a, b -> (ts[b] - ts[a]).toFloat() }
            val diffs = rr.zipWithNext { a, b -> (b - a) }
            val rmssd = if (diffs.isNotEmpty())
                sqrt(diffs.sumOf { (it * it).toDouble() } / diffs.size).toFloat()
            else 0f

            // Pulse amplitude / DC ratio (perfusion proxy).
            val dc = data.average().toFloat().coerceAtLeast(1f)
            val troughs = ArrayList<Float>()
            for (i in 1 until detrended.size - 1) {
                if (detrended[i] < 0f &&
                    detrended[i] < detrended[i - 1] &&
                    detrended[i] <= detrended[i + 1]) {
                    troughs += detrended[i]
                }
            }
            val peakVals = peakIdx.map { detrended[it] }
            if (peakVals.isEmpty() || troughs.isEmpty())
                return Triple(null, null, "low BP-signal confidence")
            val ac = peakVals.average().toFloat() - troughs.average().toFloat()
            val perfusion = ac / dc          // typically 0.005..0.03 with torch

            // Heuristic combination — calibrated so a healthy resting subject
            // (HR≈72, RMSSD≈40 ms, perfusion≈0.012) lands near 118/76 mmHg.
            val hrTerm = 0.50f * (bpm - 72f)
            val hrvTerm = -0.80f * (rmssd - 40f).coerceIn(-30f, 30f)
            val perfTerm = when {
                perfusion < 0.005f ->  8f
                perfusion > 0.018f -> -6f
                else               ->  0f
            }
            val sysF = 118f + hrTerm + hrvTerm + perfTerm
            val diaF = 76f  + 0.30f * (bpm - 72f) +
                       (-0.40f * (rmssd - 40f).coerceIn(-30f, 30f)) +
                       perfTerm * 0.5f

            val sys = sysF.toInt().coerceIn(90, 165)
            val dia = diaF.toInt().coerceIn(55, 105)
            // Sanity: pulse pressure must be > 25 mmHg.
            if (sys - dia < 25) return Triple(null, null, "low BP-signal confidence")
            return Triple(sys, dia, null)
        }
    }

    companion object {
        private const val TAG = "PpgHrMeasurer"
        const val MEASURE_DURATION_MS = 25_000L
        private const val MAX_SAMPLES = 1500
    }
}


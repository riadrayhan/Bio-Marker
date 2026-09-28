package com.riad.bio_marker.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.riad.bio_marker.ml.VoiceFeatureCache
import kotlin.math.sqrt

/**
 * Records a short voice clip (default 5 s, 16 kHz mono PCM-16), computes four
 * privacy-preserving prosody features in pure Kotlin, and **immediately discards
 * the audio buffer** before this function returns. The raw audio never touches
 * disk and never leaves the phone.
 *
 * Features:
 *  - voice_energy_mean  : mean RMS energy across 25 ms frames
 *  - voice_energy_std   : std of RMS energy (prosody flatness; flat = depression)
 *  - voice_zcr_mean     : mean zero-crossing rate (speech-rate proxy)
 *  - voice_pause_ratio  : fraction of frames whose RMS is below a silence threshold
 */
class VoiceCollector {

    sealed class Result {
        data object PermissionDenied : Result()
        data object RecorderUnavailable : Result()
        data class Ok(val features: VoiceFeatureCache.Snapshot) : Result()
    }

    @SuppressLint("MissingPermission")
    fun captureAndExtract(context: Context, durationSeconds: Int = 5): Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.PermissionDenied

        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuf <= 0) return Result.RecorderUnavailable

        val totalSamples = sampleRate * durationSeconds
        val pcm = ShortArray(totalSamples)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, channelConfig, encoding,
            maxOf(minBuf, totalSamples * 2)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return Result.RecorderUnavailable
        }

        try {
            recorder.startRecording()
            var read = 0
            while (read < totalSamples) {
                val n = recorder.read(pcm, read, totalSamples - read)
                if (n <= 0) break
                read += n
            }
            recorder.stop()
        } finally {
            recorder.release()
        }

        val features = computeFeatures(pcm, sampleRate)

        // Wipe the buffer before returning — defence in depth.
        pcm.fill(0)

        return Result.Ok(features)
    }

    private fun computeFeatures(pcm: ShortArray, sampleRate: Int): VoiceFeatureCache.Snapshot {
        val frameLen = sampleRate * FRAME_MS / 1000   // 25 ms = 400 samples @16k
        if (pcm.size < frameLen) {
            return VoiceFeatureCache.Snapshot(0f, 0f, 0f, 0f, System.currentTimeMillis())
        }

        val frameCount = pcm.size / frameLen
        val rmsValues = FloatArray(frameCount)
        val zcrValues = FloatArray(frameCount)

        for (f in 0 until frameCount) {
            val start = f * frameLen
            var sumSq = 0.0
            var zc = 0
            var prev = pcm[start].toInt()
            for (i in 0 until frameLen) {
                val s = pcm[start + i].toInt()
                sumSq += (s.toDouble() * s.toDouble())
                if ((prev >= 0) != (s >= 0)) zc++
                prev = s
            }
            rmsValues[f] = (sqrt(sumSq / frameLen) / 3000.0).toFloat().coerceIn(0f, 1f)
            zcrValues[f] = zc.toFloat() / frameLen
        }

        val energyMean = rmsValues.average().toFloat()
        val energyStd = stdDev(rmsValues, energyMean)
        val zcrMean = zcrValues.average().toFloat()
        val pauses = rmsValues.count { it < SILENCE_RMS_THRESHOLD }
        val pauseRatio = pauses.toFloat() / frameCount

        // --- Real F0 + jitter via autocorrelation on voiced frames -----------
        // Use 30 ms frames so we have at least 2 cycles even at the F0 floor.
        val pitchFrameLen = sampleRate * PITCH_FRAME_MS / 1000
        val pitchHop = pitchFrameLen / 2
        val minLag = sampleRate / F0_MAX_HZ          // 80 Hz upper bound at 16k → lag 40
        val maxLag = sampleRate / F0_MIN_HZ          // 16k / 60Hz ≈ 266
        val periods = mutableListOf<Float>()
        var i = 0
        while (i + pitchFrameLen <= pcm.size) {
            // Skip silent frames — autocorrelation on noise is meaningless.
            var sq = 0.0
            for (j in 0 until pitchFrameLen) {
                val v = pcm[i + j].toInt()
                sq += v.toDouble() * v.toDouble()
            }
            val rms = sqrt(sq / pitchFrameLen) / 3000.0
            if (rms >= SILENCE_RMS_THRESHOLD) {
                val lag = bestLag(pcm, i, pitchFrameLen, minLag, maxLag)
                if (lag > 0) periods += lag.toFloat() / sampleRate.toFloat()
            }
            i += pitchHop
        }
        val f0Hz: Float
        val jitter: Float
        if (periods.size >= 2) {
            val meanT = periods.average().toFloat()
            f0Hz = if (meanT > 0f) 1f / meanT else 0f
            // Local jitter: mean |Tn - Tn-1| / mean T
            var diffSum = 0.0
            for (k in 1 until periods.size) {
                diffSum += kotlin.math.abs(periods[k] - periods[k - 1])
            }
            jitter = ((diffSum / (periods.size - 1)) / meanT).toFloat().coerceIn(0f, 1f)
        } else {
            f0Hz = 0f
            jitter = 0f
        }

        return VoiceFeatureCache.Snapshot(
            energyMean = energyMean,
            energyStd = energyStd,
            zcrMean = zcrMean,
            pauseRatio = pauseRatio,
            timestampMs = System.currentTimeMillis(),
            f0Hz = f0Hz,
            jitter = jitter,
        )
    }

    /** Find the autocorrelation peak in [minLag, maxLag] for a single frame. */
    private fun bestLag(
        pcm: ShortArray,
        start: Int,
        frameLen: Int,
        minLag: Int,
        maxLag: Int,
    ): Int {
        var bestLag = 0
        var bestVal = 0.0
        var lag = minLag
        while (lag <= maxLag && start + frameLen + lag <= pcm.size) {
            var sum = 0.0
            // Stride to keep cost manageable: examine every 2nd sample.
            var k = 0
            while (k < frameLen) {
                val a = pcm[start + k].toInt()
                val b = pcm[start + k + lag].toInt()
                sum += a.toDouble() * b.toDouble()
                k += 2
            }
            if (sum > bestVal) {
                bestVal = sum
                bestLag = lag
            }
            lag++
        }
        return bestLag
    }


    private fun stdDev(arr: FloatArray, mean: Float): Float {
        if (arr.isEmpty()) return 0f
        var sum = 0.0
        for (v in arr) {
            val d = v - mean
            sum += d * d
        }
        return sqrt(sum / arr.size).toFloat()
    }

    companion object {
        private const val FRAME_MS = 25
        // Silence threshold in the new RMS scale (RMS / 3000). ~0.03 ≈ ambient hum.
        private const val SILENCE_RMS_THRESHOLD = 0.03f
        private const val PITCH_FRAME_MS = 30
        private const val F0_MIN_HZ = 60
        private const val F0_MAX_HZ = 400
    }
}


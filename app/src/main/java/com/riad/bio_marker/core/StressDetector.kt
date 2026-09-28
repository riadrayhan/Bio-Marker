package com.riad.bio_marker.core

import com.riad.bio_marker.ml.VoiceFeatureCache

/**
 * Cheap rule-based "is the user hyper / angry / stressed right now?" detector.
 *
 * Combines three cheap signals available without extra hardware:
 *  - **Motion energy** : sustained high accelerometer energy = restless / agitated.
 *  - **Typing speed**  : very fast keystrokes + high backspace rate = rushed / angry typing.
 *  - **Voice tone**    : negative sentiment + high pitch + low pause ratio
 *                        in the last few minutes = tense speech.
 *
 * Each contributor is normalised to 0..1, then weighted into a final score.
 * If the score crosses [HIGH_THRESHOLD] the [com.riad.bio_marker.worker.StressMonitorWorker]
 * fires an "📈 Hyper detected — quick vitals check?" notification.
 */
object StressDetector {

    /** Above this score, we treat the user as visibly stressed and offer help. */
    const val HIGH_THRESHOLD = 0.65f

    data class Result(val score: Float, val reasons: List<String>)

    fun analyze(
        snapshot: MetricsBus.Snapshot,
        voice: VoiceFeatureCache.Snapshot?,
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        val reasons = mutableListOf<String>()
        var sum = 0f
        var weight = 0f

        // 1. Motion energy — recent sample, normalised to ~typical "agitated"
        // bound (4.0 m/s² var). Capped at 1.
        val motion = (snapshot.recentMotionEnergy / 4.0f).coerceIn(0f, 1f)
        if (motion > 0.5f) reasons += "agitated movement"
        sum += motion * 0.30f; weight += 0.30f

        // 2. Typing speed — inter-key < 120 ms is "fast / rushed", combined
        //    with a backspace rate > 15 % suggests frustrated typing.
        val keys = snapshot.keystrokesToday
        val lat = snapshot.avgInterKeyLatencyMs
        val backRate =
            if (keys > 0) snapshot.backspacesToday.toFloat() / keys else 0f
        val typing = when {
            keys < 30 -> 0f
            lat in 1L..120L && backRate > 0.15f -> 1f
            lat in 1L..150L && backRate > 0.10f -> 0.7f
            else -> 0f
        }
        if (typing >= 0.7f) reasons += "fast / error-prone typing"
        sum += typing * 0.30f; weight += 0.30f

        // 3. Voice — if the latest voice sample is recent (≤ 30 min) and has
        //    a strongly negative sentiment and elevated pitch, count it.
        val voiceFresh = voice != null && nowMs - voice.timestampMs <= 30L * 60_000
        val voiceScore = if (voiceFresh && voice != null) {
            val negative = (-(voice.sentimentScore.coerceAtMost(0f))).coerceIn(0f, 1f)
            val highPitch = if (voice.f0Hz > 0f)
                ((voice.f0Hz - 180f) / 120f).coerceIn(0f, 1f) else 0f
            val noPause = (1f - voice.pauseRatio).coerceIn(0f, 1f)
            val v = 0.55f * negative + 0.30f * highPitch + 0.15f * noPause
            if (v > 0.5f) reasons += "tense speech"
            v
        } else 0f
        sum += voiceScore * 0.40f; weight += 0.40f

        val score = if (weight > 0f) (sum / weight).coerceIn(0f, 1f) else 0f
        return Result(score, reasons)
    }
}


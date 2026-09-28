package com.riad.bio_marker.ml

import com.riad.bio_marker.core.MoodTimelineStore

/**
 * Discrete mood labels produced by the voice-transcript pipeline + a tiny
 * aggregation helper retained for backward compatibility with persisted
 * [MoodTimelineStore.MoodSample] data. Face-based mood detection has been
 * removed from the app, so [summarize] now always returns a NEUTRAL verdict
 * with zero counts unless legacy samples are present.
 */
object MoodSummarizer {

    enum class Label { HAPPY, NEUTRAL, TIRED, STRESSED, SAD }

    data class Verdict(
        val dominant: Label,
        /** Share (0..1) of samples that resolved to each label. */
        val distribution: Map<Label, Float>,
        val avgSmile: Float,
        val avgEyesOpen: Float,
        val avgHeadMotion: Float,
        val sampleCount: Int,
        val negativeShare: Float,   // SAD + STRESSED + TIRED share
    )

    fun summarize(samples: List<MoodTimelineStore.MoodSample>): Verdict {
        if (samples.isEmpty()) {
            return Verdict(
                dominant = Label.NEUTRAL,
                distribution = emptyMap(),
                avgSmile = 0f, avgEyesOpen = 0f, avgHeadMotion = 0f,
                sampleCount = 0, negativeShare = 0f,
            )
        }
        val counts = HashMap<Label, Int>()
        for (s in samples) {
            val l = runCatching { Label.valueOf(s.label) }.getOrDefault(Label.NEUTRAL)
            counts[l] = (counts[l] ?: 0) + 1
        }
        val denom = samples.size.coerceAtLeast(1)
        val dist = counts.mapValues { it.value.toFloat() / denom }
        val dominant = dist.maxByOrNull { it.value }?.key ?: Label.NEUTRAL
        val negative = (dist[Label.SAD] ?: 0f) + (dist[Label.STRESSED] ?: 0f) + (dist[Label.TIRED] ?: 0f)
        return Verdict(
            dominant = dominant,
            distribution = dist,
            avgSmile = 0f,
            avgEyesOpen = 0f,
            avgHeadMotion = 0f,
            sampleCount = samples.size,
            negativeShare = negative,
        )
    }
}


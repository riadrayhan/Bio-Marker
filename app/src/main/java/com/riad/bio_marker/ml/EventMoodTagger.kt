package com.riad.bio_marker.ml

import com.riad.bio_marker.core.ConsentStore
import com.riad.bio_marker.core.DailyEvent
import com.riad.bio_marker.core.DailyEventStore
import com.riad.bio_marker.core.ServiceLocator

/**
 * Joins each [DailyEvent] with the mood signals collected during its time
 * window and writes back a single moodLabel + confidence to the event.
 *
 * Sources, in priority order:
 *  1. **Voice** ([VoiceFeatureCache]) — high jitter + low energy → STRESSED;
 *     calm prosody → CALM. Only used when [ConsentStore.Signal.VOICE_MOOD] is on.
 *  2. **Typing** — for TYPING events specifically, high backspace ratio →
 *     "stressed/correcting", low backspace + steady latency → "calm".
 *
 * If none of the above apply we leave the event without a mood (the UI just
 * shows it without a chip).
 *
 * Cheap to run — invoked once per day from [com.riad.bio_marker.worker.DailySummaryWorker].
 */
object EventMoodTagger {

    fun tagDay(date: String) {
        val store: DailyEventStore = ServiceLocator.dailyEventStore
        val events = store.load(date)
        if (events.isEmpty()) return

        val consent = ServiceLocator.consentStore
        val voiceEnabled = consent.isEnabled(ConsentStore.Signal.VOICE_MOOD)

        for (ev in events) {
            // Don't override already-tagged events on a re-run.
            if (!ev.moodLabel.isNullOrBlank()) continue

            val tagged = when (ev.type) {
                DailyEvent.Type.TYPING -> tagFromTyping(ev)
                else -> if (voiceEnabled) tagFromVoice(ev) else null
            } ?: continue

            store.updateMood(date, ev.id, tagged.first.name, tagged.second)
        }
    }

    /** Crude voice-tone heuristic from VoiceFeatureCache snapshot. */
    private fun tagFromVoice(ev: DailyEvent): Pair<MoodSummarizer.Label, Float>? {
        val cache = ServiceLocator.voiceCache
        val snap = runCatching { cache.load() }.getOrNull() ?: return null
        // Only use the snapshot if it's reasonably recent vs the event window.
        if (snap.timestampMs < ev.startMs - 6 * 3600_000L) return null
        val jitter = snap.jitter
        val energy = snap.energyMean
        return when {
            jitter > 0.04f && energy < 0.25f -> MoodSummarizer.Label.STRESSED to 0.5f
            energy > 0.5f && jitter < 0.02f -> MoodSummarizer.Label.HAPPY to 0.4f
            else -> MoodSummarizer.Label.NEUTRAL to 0.4f
        }
    }

    /**
     * Mood from typing-session metadata. The IME emits backspace count and
     * mean inter-key latency per session; high backspace rate signals
     * hesitation/agitation, low rate + steady cadence signals calm composition.
     */
    private fun tagFromTyping(ev: DailyEvent): Pair<MoodSummarizer.Label, Float>? {
        val chars = ev.metadata["chars"]?.toIntOrNull() ?: return null
        val bs = ev.metadata["backspaces"]?.toIntOrNull() ?: 0
        val avgLat = ev.metadata["avg_latency_ms"]?.toLongOrNull() ?: 0L
        if (chars < 20) return null
        val bsRate = bs.toFloat() / chars
        return when {
            bsRate > 0.20f -> MoodSummarizer.Label.STRESSED to 0.55f
            bsRate < 0.05f && avgLat in 80..600 -> MoodSummarizer.Label.NEUTRAL to 0.55f
            else -> MoodSummarizer.Label.NEUTRAL to 0.4f
        }
    }
}




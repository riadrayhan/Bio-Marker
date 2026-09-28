package com.riad.bio_marker.ml

import android.content.Context

/**
 * Persists the most recent voice check-in's features.
 * Raw audio is never stored — only these floats + (optionally) a tiny set of
 * transcript-derived counters (no transcript text is stored).
 */
class VoiceFeatureCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_voice", Context.MODE_PRIVATE)

    data class Snapshot(
        val energyMean: Float,
        val energyStd: Float,
        val zcrMean: Float,
        val pauseRatio: Float,
        val timestampMs: Long,
        // --- Real prosody, computed via autocorrelation in VoiceCollector ---
        val f0Hz: Float = 0f,
        val jitter: Float = 0f,
        // --- Transcript-derived (counts/scores only, never the text itself) ---
        val wordCount: Int = 0,
        val hesitationCount: Int = 0,
        val negativeWordCount: Int = 0,
        val positiveWordCount: Int = 0,
        val sentimentScore: Float = 0f,
        val hasTranscript: Boolean = false,
    )

    fun save(s: Snapshot) {
        prefs.edit()
            .putFloat(K_ENERGY_MEAN, s.energyMean)
            .putFloat(K_ENERGY_STD, s.energyStd)
            .putFloat(K_ZCR, s.zcrMean)
            .putFloat(K_PAUSE, s.pauseRatio)
            .putLong(K_TS, s.timestampMs)
            .putFloat(K_F0, s.f0Hz)
            .putFloat(K_JITTER, s.jitter)
            .putInt(K_WORDS, s.wordCount)
            .putInt(K_HES, s.hesitationCount)
            .putInt(K_NEG, s.negativeWordCount)
            .putInt(K_POS, s.positiveWordCount)
            .putFloat(K_SENT, s.sentimentScore)
            .putBoolean(K_HAS_TX, s.hasTranscript)
            .apply()
    }

    fun load(): Snapshot? {
        val ts = prefs.getLong(K_TS, 0L)
        if (ts == 0L) return null
        return Snapshot(
            energyMean = prefs.getFloat(K_ENERGY_MEAN, 0f),
            energyStd = prefs.getFloat(K_ENERGY_STD, 0f),
            zcrMean = prefs.getFloat(K_ZCR, 0f),
            pauseRatio = prefs.getFloat(K_PAUSE, 0f),
            timestampMs = ts,
            f0Hz = prefs.getFloat(K_F0, 0f),
            jitter = prefs.getFloat(K_JITTER, 0f),
            wordCount = prefs.getInt(K_WORDS, 0),
            hesitationCount = prefs.getInt(K_HES, 0),
            negativeWordCount = prefs.getInt(K_NEG, 0),
            positiveWordCount = prefs.getInt(K_POS, 0),
            sentimentScore = prefs.getFloat(K_SENT, 0f),
            hasTranscript = prefs.getBoolean(K_HAS_TX, false),
        )
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val K_ENERGY_MEAN = "energy_mean"
        private const val K_ENERGY_STD = "energy_std"
        private const val K_ZCR = "zcr"
        private const val K_PAUSE = "pause"
        private const val K_TS = "ts"
        private const val K_F0 = "f0_hz"
        private const val K_JITTER = "jitter"
        private const val K_WORDS = "words"
        private const val K_HES = "hesitations"
        private const val K_NEG = "neg_words"
        private const val K_POS = "pos_words"
        private const val K_SENT = "sentiment"
        private const val K_HAS_TX = "has_transcript"
    }
}

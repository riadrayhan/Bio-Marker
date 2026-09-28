package com.riad.bio_marker.collectors

import android.util.Log
import com.riad.bio_marker.core.ConsentStore
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.VoiceTranscriptStore

/**
 * Captures the **content** the user types on the in-app [BioMarkerIME] and
 * stores it next to the spoken-word transcripts in [VoiceTranscriptStore],
 * so the end-of-window Groq agent makes a single mood decision from BOTH
 * what the user *said* and what they *typed*.
 *
 * Privacy & gating — typed text is only ever persisted when **all** of:
 *   1. a monitoring window is currently active (and not expired), and
 *   2. the user has granted the TYPING_SESSIONS consent signal.
 * Outside an active window (the common case) nothing is stored, and the
 * user can clear the whole transcript log any time from Voice notes.
 *
 * The IME buffers characters and calls [capture] on natural boundaries
 * (sentence end / Enter / a few seconds of inactivity / field change), so
 * we store readable phrases rather than isolated keystrokes.
 */
object TypedTextCollector {

    private const val TAG = "TypedTextCollector"

    /** Persist one chunk of typed text, after the same lexicon sentiment
     *  analysis the voice path uses. No-op when gating conditions fail. */
    fun capture(rawText: String) {
        // Never let a capture failure crash the keyboard the user is typing on.
        runCatching { captureInner(rawText) }
            .onFailure { Log.w(TAG, "capture failed: ${it.message}") }
    }

    private fun captureInner(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return

        // The store/locator must be initialised (it is, from Application.onCreate
        // in every process — but guard anyway since the IME can be the first
        // component to spin up a fresh process).
        if (!ServiceLocator.isReady()) {
            Log.w(TAG, "ServiceLocator not ready — dropping typed text")
            return
        }

        // Capture while a monitoring window is active (not expired) OR while the
        // collection service is running — i.e. whenever the user has explicitly
        // started monitoring. Outside that, store nothing.
        val session = ServiceLocator.monitoringStore.current()
        val windowActive = session != null && !session.isExpired()
        val serviceRunning = ServiceLocator.metricsBus.state.value.serviceRunning
        if (!windowActive && !serviceRunning) {
            Log.d(TAG, "No active window / service — typed text not stored")
            return
        }

        // Respect the typing-sessions consent toggle.
        if (!ServiceLocator.consentStore.isEnabled(ConsentStore.Signal.TYPING_SESSIONS)) {
            Log.d(TAG, "TYPING_SESSIONS consent off — typed text not stored")
            return
        }

        val windowStartMs = session?.startedAtMs ?: 0L
        val analysis = TextAnalyzer.analyse(text)
        val mood = when {
            analysis.sentimentScore > 0.05f -> "positive"
            analysis.sentimentScore < -0.05f -> "negative"
            else -> "neutral"
        }
        ServiceLocator.voiceTranscriptStore.append(
            VoiceTranscriptStore.Entry(
                windowStartMs = windowStartMs,
                timestampMs = System.currentTimeMillis(),
                text = text,
                lang = "keyboard",
                wordCount = analysis.wordCount,
                sentimentScore = analysis.sentimentScore,
                mood = mood,
                source = "keyboard",
            )
        )
        Log.d(TAG, "Stored typed text (${analysis.wordCount} words): ${text.take(40)}")
    }
}


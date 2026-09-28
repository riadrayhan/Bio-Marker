package com.riad.bio_marker.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the master consent flag plus per-signal toggles. Each signal can be
 * disabled independently from Settings without revoking overall consent.
 *
 * The granular flags default to `hasConsent` so users upgrading from earlier
 * builds keep their previous behaviour.
 */
class ConsentStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("biomarker_consent", Context.MODE_PRIVATE)

    var hasConsent: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT, value).apply()

    var consentTimestamp: Long
        get() = prefs.getLong(KEY_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_TIMESTAMP, value).apply()

    enum class Signal(val key: String) {
        MOTION("c_motion"),
        KEYSTROKE("c_keystroke"),
        VOICE("c_voice"),
        USAGE("c_usage"),
        LOCATION("c_location"),
        SLEEP("c_sleep"),
        CLINICIAN_SYNC("c_sync"),
        FEDERATED_LEARNING("c_fl"),

        // --- Per-event timeline signals (Bio Marker activity events) ---
        /** Real-time per-call session events via TelephonyCallback. */
        CALL_EVENTS("c_call_events"),
        /** Foreground-app sessions (timeline of which app, when, how long). */
        APP_SESSIONS("c_app_sessions"),
        /** Typing-session events emitted by the in-app IME. */
        TYPING_SESSIONS("c_typing_sessions"),
        /** Walking-bout events (start / end of contiguous walking). */
        WALK_BOUTS("c_walk_bouts"),
        /** Allow EventMoodTagger to attach voice-derived mood to events. */
        VOICE_MOOD("c_voice_mood"),
        /** Best-effort meeting/conversation detection (mic VAD heuristic). */
        MEETING("c_meeting"),
    }

    fun isEnabled(signal: Signal): Boolean {
        if (!hasConsent) return false
        // Location, clinician-sync, FL, and the privacy-sensitive event
        // signals default to OFF (opt-in).
        // The rest default to ON when overall consent is granted.
        val default = when (signal) {
            Signal.LOCATION,
            Signal.CLINICIAN_SYNC,
            Signal.FEDERATED_LEARNING,
            Signal.CALL_EVENTS,
            Signal.MEETING -> false
            else -> true
        }
        return prefs.getBoolean(signal.key, default)
    }

    fun setEnabled(signal: Signal, enabled: Boolean) {
        prefs.edit().putBoolean(signal.key, enabled).apply()
    }

    fun revoke() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_CONSENT = "consent_granted"
        private const val KEY_TIMESTAMP = "consent_ts"
    }
}

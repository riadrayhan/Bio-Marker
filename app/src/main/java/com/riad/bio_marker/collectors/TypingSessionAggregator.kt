package com.riad.bio_marker.collectors

import com.riad.bio_marker.core.ConsentStore
import com.riad.bio_marker.core.DailyEvent
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.recordEvent


object TypingSessionAggregator {

    private var firstKeyMs: Long = 0L
    private var lastKeyMs: Long = 0L
    private var keyCount: Int = 0
    private var backspaceCount: Int = 0
    private var latencySum: Long = 0L
    private var latencyN: Int = 0

    @Synchronized
    fun onKeystroke(timestampMs: Long, latencyMs: Long, isBackspace: Boolean) {
        // If the gap from the previous keystroke is too large, the previous
        // session is over — flush it first, then start a new one.
        if (lastKeyMs != 0L && (timestampMs - lastKeyMs) > SESSION_GAP_MS) {
            flushLocked(closingMs = lastKeyMs)
        }
        if (firstKeyMs == 0L) firstKeyMs = timestampMs
        lastKeyMs = timestampMs
        keyCount += 1
        if (isBackspace) backspaceCount += 1
        if (latencyMs > 0L) {
            latencySum += latencyMs
            latencyN += 1
        }
    }

    /** Force-emit any in-progress session (called when service stops or day rolls). */
    @Synchronized
    fun flush() {
        flushLocked(closingMs = if (lastKeyMs != 0L) lastKeyMs else 0L)
    }

    private fun flushLocked(closingMs: Long) {
        val s = firstKeyMs
        val e = closingMs
        val n = keyCount
        // Reset BEFORE emitting so re-entry from the recordEvent path is safe.
        firstKeyMs = 0L
        lastKeyMs = 0L
        keyCount = 0
        val bs = backspaceCount
        backspaceCount = 0
        val ls = latencySum
        val ln = latencyN
        latencySum = 0L
        latencyN = 0
        if (s == 0L || e == 0L || n < MIN_KEYS) return
        if ((e - s) < MIN_DURATION_MS) return
        val avgLat = if (ln > 0) (ls / ln) else 0L
        ServiceLocator.recordEvent(
            type = DailyEvent.Type.TYPING,
            startMs = s,
            endMs = e,
            source = "ime",
            metadata = mapOf(
                "chars" to n.toString(),
                "backspaces" to bs.toString(),
                "avg_latency_ms" to avgLat.toString(),
            ),
            requiredSignal = ConsentStore.Signal.TYPING_SESSIONS,
        )
    }

    /** Inactivity gap that closes a typing session (60 s without a key press). */
    private const val SESSION_GAP_MS = 60_000L
    private const val MIN_KEYS = 8
    private const val MIN_DURATION_MS = 5_000L
}


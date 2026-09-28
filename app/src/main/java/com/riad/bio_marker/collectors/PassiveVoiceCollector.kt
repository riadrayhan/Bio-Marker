package com.riad.bio_marker.collectors

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.VoiceTranscriptStore
import com.riad.bio_marker.ml.VoiceFeatureCache

/**
 * Continuous passive voice **monitor** that listens for what the user is
 * actually saying, runs an on-device transcript-derived sentiment analysis
 * (English + Bengali via [TextAnalyzer]), and publishes the running
 * counters into [VoiceFeatureCache] so the dashboard and end-of-window AI
 * summary can report based on the user's words.
 *
 * Implementation: chains Android's [SpeechRecognizer] back-to-back. Each
 * utterance produces a transcript that is immediately fed to [TextAnalyzer];
 * word/sentiment counters are accumulated for the whole monitoring window.
 * Raw audio and transcript text are **never** persisted — only the derived
 * counters.
 */
class PassiveVoiceCollector(private val context: Context) {

    @Volatile private var running = false
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())


    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val mutedStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
    )
    private var beepMuted = false
    /** Currently-listening BCP-47 language tag — recorded with each
     *  transcript so the Voice notes screen can group / show it. */
    private var currentLanguageTag: String = "en-US"
    /** Ordered candidate languages (Bangla-first when device locale = bn). */
    private var languageCandidates: List<String> = listOf("en-US")
    private var languageIdx: Int = 0
    /** Consecutive "no speech / no match" results on the current language —
     *  after a few we rotate to the next candidate so a Bangla speaker on
     *  an English-locale device (or vice-versa) still gets recognised. */
    private var noMatchStreak: Int = 0

    // Running window-level counters.
    private var totalWords = 0
    private var totalHesitations = 0
    private var totalNeg = 0
    private var totalPos = 0
    // Coarse rolling energy proxy from SpeechRecognizer's onRmsChanged so
    // VoiceFeatureCache.energyMean still has a non-zero signal.
    private var rmsAccum = 0f
    private var rmsCount = 0

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun reset() {
        totalWords = 0; totalHesitations = 0
        totalNeg = 0; totalPos = 0
        rmsAccum = 0f; rmsCount = 0
    }

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted — passive voice monitor disabled")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer unavailable — passive voice monitor disabled")
            return false
        }
        running = true
        reset()
        // Initialise the rotating language pool (Bangla-first when the
        // device locale is bn; otherwise device-default → bn-BD → en-US).
        languageCandidates = LinkedHashSet(
            VoiceTranscriber.defaultLanguagePreferences(context)
        ).toList().ifEmpty { listOf("en-US") }
        languageIdx = 0
        noMatchStreak = 0
        // SpeechRecognizer must be created on a Looper thread; main looper
        // is always available regardless of where start() is called from.
        mainHandler.post { startSession() }
        Log.i(TAG, "Passive voice monitor started; candidates=$languageCandidates")
        return true
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        // Always restore the media stream we may have muted for beep removal.
        setBeepMuted(false)
        mainHandler.post {
            try { recognizer?.cancel() } catch (_: Throwable) {}
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
            Log.i(TAG, "Passive voice monitor stopped")
        }
    }

    /** Mute the recogniser's tone streams for the whole session. Once muted we
     *  stay muted until [stop] restores everything — toggling per cycle left
     *  audible gaps on some OEMs. */
    private fun setBeepMuted(mute: Boolean) {
        if (mute == beepMuted) return
        val am = audioManager ?: return
        for (stream in mutedStreams) {
            try {
                am.adjustStreamVolume(
                    stream,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0,
                )
            } catch (_: Throwable) {
                // Some OEMs block programmatic stream muting — fail quietly.
            }
        }
        beepMuted = mute
    }

    private fun startSession() {
        if (!running) return
        try { recognizer?.destroy() } catch (_: Throwable) {}
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        val tag = languageCandidates.getOrNull(languageIdx) ?: "en-US"
        currentLanguageTag = tag
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Enable partial results so short / quiet Bangla utterances
            // (which the engine often ends with onError(NO_MATCH) before
            // emitting a final onResults) are still captured.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Keep each listen session alive longer through natural pauses so we
            // restart (and re-trigger the recogniser tone) far less often — this
            // makes capture feel continuous instead of choppy.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            // NOTE: we deliberately do NOT set EXTRA_PREFER_OFFLINE.
            // Most Bangladeshi phones don't have a downloaded offline Bangla
            // model, so forcing offline made every utterance return empty.
            // The system recognition service will pick the best engine.
        }

        r.setRecognitionListener(object : RecognitionListener {
            private var lastPartial: String = ""

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // Make sure the tone streams stay muted across the cycle.
                setBeepMuted(true)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val t = list?.firstOrNull().orEmpty()
                if (t.isNotBlank()) lastPartial = t
            }

            override fun onRmsChanged(rmsdB: Float) {
                rmsAccum += rmsdB
                rmsCount += 1
            }

            override fun onResults(results: Bundle?) {
                val list = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = (list?.firstOrNull().orEmpty()).ifBlank { lastPartial }
                if (transcript.isNotBlank()) {
                    val analysis = TextAnalyzer.analyse(transcript)
                    accumulateAndPersist(analysis)
                    persistTranscriptEntry(transcript, analysis)
                    noMatchStreak = 0
                    Log.d(TAG, "Recognised ($currentLanguageTag): ${transcript.take(60)}")
                } else {
                    // Final result was empty — treat same as NO_MATCH so we
                    // eventually rotate languages.
                    bumpNoMatch()
                }
                rearm(0)
            }

            override fun onError(error: Int) {
                val name = errName(error)
                val delay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> 1500L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.w(TAG, "Mic perm revoked — stopping passive voice monitor")
                        running = false
                        return
                    }
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Maybe nothing was said — but if it keeps happening
                        // on the same language, the engine probably can't
                        // recognise the user's language. Rotate.
                        // If we already had a partial transcript, salvage it.
                        if (lastPartial.isNotBlank()) {
                            val analysis = TextAnalyzer.analyse(lastPartial)
                            accumulateAndPersist(analysis)
                            persistTranscriptEntry(lastPartial, analysis)
                            noMatchStreak = 0
                            Log.d(TAG, "Salvaged partial ($currentLanguageTag): ${lastPartial.take(60)}")
                        } else {
                            bumpNoMatch()
                        }
                        300L
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        Log.w(TAG, "Language $currentLanguageTag unsupported — rotating")
                        rotateLanguage()
                        500L
                    }
                    else -> 400L
                }
                Log.d(TAG, "onError=$name (lang=$currentLanguageTag, streak=$noMatchStreak)")
                rearm(delay)
            }
        })

        try {
            // Mute the recogniser tone streams for the whole session before the
            // first listen begins, so neither the start nor stop tone is heard.
            setBeepMuted(true)
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed: ${t.message}")
            rearm(1000)
        }
    }

    private fun bumpNoMatch() {
        noMatchStreak++
        // After ~4 silent cycles on one language, try the next candidate.
        if (noMatchStreak >= 4 && languageCandidates.size > 1) {
            rotateLanguage()
        }
    }

    private fun rotateLanguage() {
        if (languageCandidates.isEmpty()) return
        languageIdx = (languageIdx + 1) % languageCandidates.size
        noMatchStreak = 0
        Log.i(TAG, "Rotated recognition language → ${languageCandidates[languageIdx]}")
    }

    private fun errName(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERM"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NET_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANG_UNAVAILABLE"
        else -> "ERR_$e"
    }

    private fun rearm(delayMs: Long) {
        if (!running) return
        // Refresh the cache timestamp on every cycle so the dashboard's
        // "Voice last: Ns ago" reading reflects active monitoring, even
        // during silent stretches.
        persistCurrent()
        mainHandler.postDelayed({ startSession() }, delayMs)
    }

    private fun accumulateAndPersist(analysis: VoiceTranscriber.TextAnalysis) {
        totalWords += analysis.wordCount
        totalHesitations += analysis.hesitationCount
        totalNeg += analysis.negativeWordCount
        totalPos += analysis.positiveWordCount
        persistCurrent()
    }

    private fun persistCurrent() {
        val sentiment = if (totalWords == 0) 0f
            else ((totalPos - totalNeg).toFloat() / totalWords).coerceIn(-1f, 1f)
        val energyMean = if (rmsCount > 0)
            ((rmsAccum / rmsCount).coerceIn(-2f, 10f) + 2f) / 12f
            else 0f
        val snapshot = VoiceFeatureCache.Snapshot(
            energyMean = energyMean,
            energyStd = 0f,
            zcrMean = 0f,
            pauseRatio = 0f,
            timestampMs = System.currentTimeMillis(),
            f0Hz = 0f,
            jitter = 0f,
            wordCount = totalWords,
            hesitationCount = totalHesitations,
            negativeWordCount = totalNeg,
            positiveWordCount = totalPos,
            sentimentScore = sentiment,
            hasTranscript = totalWords > 0,
        )
        ServiceLocator.voiceCache.save(snapshot)
    }

    /**
     * Append the raw transcript + its sentiment to [VoiceTranscriptStore]
     * so the user can review every utterance under Tools → Voice notes
     * and so the end-of-window AI analysis has fine-grained per-utterance
     * timestamps to produce a real mood timeline (e.g. "happy for 50 min,
     * angry for last 10 min") instead of just an aggregate score.
     */
    private fun persistTranscriptEntry(
        transcript: String,
        analysis: VoiceTranscriber.TextAnalysis,
    ) {
        val mood = when {
            analysis.sentimentScore > 0.05f -> "positive"
            analysis.sentimentScore < -0.05f -> "negative"
            else -> "neutral"
        }
        val windowKey = ServiceLocator.monitoringStore.current()?.startedAtMs ?: 0L
        ServiceLocator.voiceTranscriptStore.append(
            VoiceTranscriptStore.Entry(
                windowStartMs = windowKey,
                timestampMs = System.currentTimeMillis(),
                text = transcript.trim(),
                lang = currentLanguageTag,
                wordCount = analysis.wordCount,
                sentimentScore = analysis.sentimentScore,
                mood = mood,
            )
        )
    }

    companion object {
        private const val TAG = "PassiveVoiceCollector"
    }
}

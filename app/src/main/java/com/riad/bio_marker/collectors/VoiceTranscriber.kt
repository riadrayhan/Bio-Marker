package com.riad.bio_marker.collectors

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.riad.bio_marker.ml.VoiceFeatureCache
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Wraps Android's [SpeechRecognizer] and runs a simple, fully-offline-capable
 * lexicon analysis on the resulting transcript.
 *
 * Language strategy (Bengali-first when appropriate):
 *  - If the user's device locale is Bengali (bn / bn-BD / bn-IN) we try
 *    `bn-BD`, then `bn-IN`, then `en-US`.
 *  - Otherwise we try the device-default language tag, then fall back to
 *    `en-US`.
 *  Each candidate is attempted in turn; the first non-null transcription
 *  wins. This means a user speaking Bangla will not silently get an empty
 *  result just because the global default was English.
 *
 * **Privacy:** the transcript text itself is *never* persisted — only a few
 * derived counters (word count, hesitation count, pos/neg word counts and
 * an aggregate sentiment score) are written to [VoiceFeatureCache].
 *
 * Should be run from a coroutine on the main thread (`Dispatchers.Main`)
 * because `SpeechRecognizer` requires a `Looper`.
 */
object VoiceTranscriber {

    /**
     * Run a one-shot speech-to-text recognition and return the analysis.
     * Tries the supplied language tags in order and returns the first
     * successful transcription. Returns null if recognition isn't
     * available, RECORD_AUDIO is missing, or every language attempt failed.
     *
     * Caller must already hold RECORD_AUDIO.
     */
    suspend fun transcribeOnce(
        context: Context,
        languageTags: List<String> = defaultLanguagePreferences(context),
    ): TextAnalysis? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null

        // Deduplicate while preserving order (LinkedHashSet).
        val ordered = LinkedHashSet(languageTags).toList()
        for (tag in ordered) {
            val r = transcribeWithLanguage(context, tag) ?: continue
            // Reject empty / whitespace-only transcripts so we fall through
            // to the next language candidate (e.g. Bengali → English).
            if (r.wordCount == 0) continue
            return r
        }
        return null
    }

    /** Primary entry-point used by the UI. Picks the best language list for
     *  the current device locale (Bengali-first when applicable). */
    fun defaultLanguagePreferences(context: Context): List<String> {
        val cfg = context.resources.configuration
        val deviceLocale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cfg.locales[0]
        } else {
            @Suppress("DEPRECATION") cfg.locale
        }
        val deviceTag = deviceLocale.toLanguageTag()
        return when (deviceLocale.language) {
            "bn" -> listOf("bn-BD", "bn-IN", deviceTag, "en-US")
            else -> listOf(deviceTag, "bn-BD", "en-US")
        }
    }

    private suspend fun transcribeWithLanguage(
        context: Context,
        languageTag: String,
    ): TextAnalysis? = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            // Hint to the engine: prefer this language even if the system
            // default differs.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Capture partial results so short Bangla utterances are not
            // lost when the engine ends with NO_MATCH before producing a
            // final transcript.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // NOTE: deliberately not forcing EXTRA_PREFER_OFFLINE — most
            // devices don't have an offline Bangla model and forcing it
            // makes recognition silently fail.
        }

        var resumed = false
        var lastPartial = ""
        fun finish(result: TextAnalysis?) {
            if (resumed) return
            resumed = true
            try { recognizer.destroy() } catch (_: Throwable) {}
            cont.resume(result)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // Salvage the most recent partial when the engine errors
                // out with NO_MATCH / SPEECH_TIMEOUT — better than dropping
                // what we already heard.
                if (lastPartial.isNotBlank()) {
                    finish(TextAnalyzer.analyse(lastPartial))
                } else finish(null)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val t = list?.firstOrNull().orEmpty()
                if (t.isNotBlank()) lastPartial = t
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val list = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = (list?.firstOrNull().orEmpty()).ifBlank { lastPartial }
                finish(TextAnalyzer.analyse(transcript))
            }
        })

        cont.invokeOnCancellation {
            try { recognizer.destroy() } catch (_: Throwable) {}
        }

        try {
            recognizer.startListening(intent)
        } catch (_: Throwable) {
            finish(null)
        }
    }

    /** Light-weight transcript-derived counters. The text itself stays here
     *  in memory only and is never written to disk. */
    data class TextAnalysis(
        val wordCount: Int,
        val hesitationCount: Int,
        val negativeWordCount: Int,
        val positiveWordCount: Int,
        val sentimentScore: Float, // (pos - neg) / max(1, words), clipped to [-1, 1]
    ) {
        /** Merge into an existing acoustic-only snapshot. */
        fun applyTo(s: VoiceFeatureCache.Snapshot): VoiceFeatureCache.Snapshot =
            s.copy(
                wordCount = wordCount,
                hesitationCount = hesitationCount,
                negativeWordCount = negativeWordCount,
                positiveWordCount = positiveWordCount,
                sentimentScore = sentimentScore,
                hasTranscript = true,
            )
    }
}

/**
 * Tiny, fully-on-device lexicon-based text analyser. Deliberately simple:
 * no ML, no network, deterministic, ~free CPU/memory.
 *
 * Bilingual (English + Bengali). Tokenisation uses a Unicode-aware regex
 * so Bangla glyphs are preserved (the previous `[^a-z']+` regex stripped
 * every non-ASCII letter and made the analyser useless on Bengali input).
 */
object TextAnalyzer {

    // ----- English lexicons -----
    private val HESITATIONS_EN = setOf(
        "um", "uh", "uhh", "umm", "er", "err", "ah", "ahh", "hm", "hmm",
        "like",
    )
    private val NEGATIVE_EN = setOf(
        "sad", "tired", "exhausted", "lonely", "alone", "anxious", "worried",
        "stressed", "depressed", "down", "low", "hopeless", "empty", "afraid",
        "scared", "angry", "upset", "hurt", "bad", "terrible", "awful",
        "miserable", "crying", "cry", "hate", "useless", "worthless",
        "broken", "lost", "dark", "fear", "panic", "numb",
    )
    private val POSITIVE_EN = setOf(
        "good", "great", "happy", "calm", "peaceful", "fine", "okay", "ok",
        "better", "well", "nice", "lovely", "excited", "hopeful", "grateful",
        "thankful", "love", "loved", "joy", "smile", "smiling", "relaxed",
        "energetic", "motivated", "proud", "rested",
    )

    // ----- Bengali (Bangla) lexicons -----
    // Filler / hesitation tokens commonly used in Bangla speech.
    private val HESITATIONS_BN = setOf(
        "মানে",   // "mane" — "i mean"
        "ইয়ে",   // "ye"   — generic filler
        "এইটা",   // "eita" — "this thing"
        "আম",    // partial "amm…"
        "আহ",
        "উম",
    )
    private val NEGATIVE_BN = setOf(
        "দুঃখ",       // sadness
        "দুঃখিত",     // sad / sorry
        "একা",        // alone
        "একাকী",      // lonely
        "ক্লান্ত",     // tired
        "ক্লান্তি",    // tiredness
        "চিন্তিত",    // worried
        "ভয়",         // fear
        "ভীত",        // scared
        "বিষণ্ন",      // depressed / gloomy
        "হতাশ",       // hopeless / dejected
        "রাগ",        // anger
        "রাগান্বিত",   // angry
        "কষ্ট",        // pain / suffering
        "খারাপ",      // bad
        "ভালোনা",     // "not good"  — common compact form
        "মন",         // appears in "মন খারাপ" — needs bigram, see below
        // "মন খারাপ"  bigram handled separately
        "অসুস্থ",      // sick / unwell
        "ঘৃণা",        // hatred
        "অশান্ত",     // restless / agitated
        "আতঙ্ক",      // panic
        "অসহায়",     // helpless
        "দুর্বল",      // weak
    )
    private val POSITIVE_BN = setOf(
        "ভালো",       // good
        "ভাল",        // alt spelling
        "চমৎকার",    // excellent
        "দারুণ",      // great / awesome
        "খুশি",        // happy
        "আনন্দ",      // joy
        "সুখী",        // happy / content
        "শান্ত",       // calm
        "শান্তি",      // peace
        "প্রিয়",       // dear / loved
        "ভালোবাসা",  // love
        "ধন্যবাদ",    // thanks
        "কৃতজ্ঞ",     // grateful
        "সুস্থ",        // healthy
        "তরতাজা",    // refreshed
        "উৎসাহী",    // enthusiastic
        "আশাবাদী",   // hopeful
        "গর্বিত",      // proud
    )

    /** Bigrams that need special handling (regex on the original lowered
     *  string, before tokenisation strips spaces). */
    private val NEGATIVE_BIGRAMS_BN = listOf(
        "মন খারাপ",      // "feeling down"
        "ভালো লাগছে না",  // "doesn't feel good"
        "ভালো লাগে না",   // same, alt
    )
    private val POSITIVE_BIGRAMS_BN = listOf(
        "ভালো লাগছে",    // "feeling good"
        "ভালো লাগে",     // "feels good"
        "মন ভালো",       // "feeling good"
    )

    /** Token-splitter: keep Unicode letters & digits, split on everything
     *  else. `\p{L}` covers Latin, Bengali and any other script. */
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}']+")

    fun analyse(rawTranscript: String): VoiceTranscriber.TextAnalysis {
        if (rawTranscript.isBlank()) {
            return VoiceTranscriber.TextAnalysis(0, 0, 0, 0, 0f)
        }
        val normalized = rawTranscript.lowercase()
        var hesitations = 0

        // 1) Multi-word fillers / sentiment phrases on the still-spaced text.
        var stripped = normalized
        // English bigram "you know" — kept as a hesitation marker.
        var idx = stripped.indexOf("you know")
        while (idx >= 0) {
            hesitations++
            stripped = stripped.substring(0, idx) + " " + stripped.substring(idx + 8)
            idx = stripped.indexOf("you know")
        }

        // 2) Bengali sentiment bigrams. Counted once per occurrence,
        //    and the matched phrase is masked out so its component words
        //    aren't double-counted by the unigram pass.
        var negFromBigrams = 0
        var posFromBigrams = 0
        for (phrase in NEGATIVE_BIGRAMS_BN) {
            var i = stripped.indexOf(phrase)
            while (i >= 0) {
                negFromBigrams++
                stripped = stripped.substring(0, i) +
                    " ".repeat(phrase.length) +
                    stripped.substring(i + phrase.length)
                i = stripped.indexOf(phrase)
            }
        }
        for (phrase in POSITIVE_BIGRAMS_BN) {
            var i = stripped.indexOf(phrase)
            while (i >= 0) {
                posFromBigrams++
                stripped = stripped.substring(0, i) +
                    " ".repeat(phrase.length) +
                    stripped.substring(i + phrase.length)
                i = stripped.indexOf(phrase)
            }
        }

        // 3) Unigram pass over Unicode-aware tokens.
        val tokens = stripped
            .split(TOKEN_SPLIT)
            .filter { it.isNotBlank() }

        var neg = negFromBigrams
        var pos = posFromBigrams
        for (t in tokens) {
            when (t) {
                in HESITATIONS_EN, in HESITATIONS_BN -> hesitations++
                in NEGATIVE_EN,    in NEGATIVE_BN    -> neg++
                in POSITIVE_EN,    in POSITIVE_BN    -> pos++
            }
        }
        val words = tokens.size
        val sentiment = if (words == 0) 0f
        else ((pos - neg).toFloat() / words.toFloat()).coerceIn(-1f, 1f)

        return VoiceTranscriber.TextAnalysis(
            wordCount = words,
            hesitationCount = hesitations,
            negativeWordCount = neg,
            positiveWordCount = pos,
            sentimentScore = sentiment,
        )
    }
}




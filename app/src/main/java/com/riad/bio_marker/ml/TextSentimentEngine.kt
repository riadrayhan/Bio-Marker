package com.riad.bio_marker.ml

/**
 * Lightweight on-device sentiment classifier for typed text snippets.
 *
 * **Important:** the production app never inspects the user's typed text —
 * the IME only forwards keystroke timing and a backspace flag. This engine
 * is a *tool* exposed for future hooks (e.g. a clipboard-only mood survey
 * field, or self-reported journal entry) where the user has explicitly
 * pasted text in.
 *
 * v1 implementation: a tiny lexicon-based scorer. Replace with a TFLite
 * model loaded from `assets/text_sentiment.tflite` once
 * [model_training/train_text_sentiment.py] has produced one — the public
 * API ([classify]) stays the same.
 */
object TextSentimentEngine {

    enum class Label { POSITIVE, NEUTRAL, NEGATIVE }

    data class Result(val label: Label, val score: Float)

    private val POSITIVE = setOf(
        // English
        "good", "great", "happy", "love", "fine", "ok", "okay", "calm",
        "thanks", "thank", "awesome", "nice", "cool", "yes", "yay", "haha",
        "lol", "hehe", "smile",
        // Bengali (Bangla)
        "ভালো", "ভাল", "চমৎকার", "দারুণ", "খুশি", "আনন্দ", "সুখী",
        "শান্ত", "শান্তি", "ভালোবাসা", "ধন্যবাদ", "কৃতজ্ঞ", "সুস্থ",
    )
    private val NEGATIVE = setOf(
        // English
        "bad", "sad", "angry", "hate", "tired", "stressed", "anxious",
        "worried", "depressed", "annoyed", "ugh", "wtf", "fuck", "damn",
        "exhausted", "lonely", "frustrated", "sucks",
        // Bengali (Bangla)
        "দুঃখ", "দুঃখিত", "একা", "একাকী", "ক্লান্ত", "চিন্তিত", "ভয়",
        "ভীত", "বিষণ্ন", "হতাশ", "রাগ", "রাগান্বিত", "কষ্ট", "খারাপ",
        "অসুস্থ", "ঘৃণা", "অশান্ত", "আতঙ্ক",
    )

    /** Returns NEUTRAL with score 0 for empty / very short input. */
    fun classify(text: String): Result {
        val tokens = text.lowercase()
            // Unicode-aware split — preserves Bengali glyphs (the previous
            // ASCII-only regex stripped them and made Bangla input score 0).
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.isNotBlank() }
        if (tokens.size < 3) return Result(Label.NEUTRAL, 0f)
        var pos = 0; var neg = 0
        for (t in tokens) {
            if (t in POSITIVE) pos += 1
            if (t in NEGATIVE) neg += 1
        }
        val net = pos - neg
        val score = net.toFloat() / tokens.size.coerceAtLeast(1)
        val label = when {
            score > 0.05f -> Label.POSITIVE
            score < -0.05f -> Label.NEGATIVE
            else -> Label.NEUTRAL
        }
        return Result(label, score.coerceIn(-1f, 1f))
    }
}


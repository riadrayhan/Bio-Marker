package com.riad.bio_marker.core

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


object TextTranslator {

    private const val TAG = "TextTranslator"

    /** Cache of `sourceLang→targetLang` translators, lazily created. */
    private val cache = mutableMapOf<Pair<String, String>, Translator>()

    /**
     * Detects the dominant language of [text] (returns a BCP-47 tag like
     * `bn`, `en`, or `und` for "undetermined").
     */
    suspend fun detectLanguage(text: String): String =
        suspendCancellableCoroutine { cont ->
            if (text.isBlank()) { cont.resume("und"); return@suspendCancellableCoroutine }
            // Cheap heuristic first: any Bengali codepoint → bn. The ML Kit
            // language identifier downloads its own 5 MB model the first
            // time it runs, so for the common Bengali / English mix we
            // skip that with a Unicode-range check.
            if (text.any { it.code in 0x0980..0x09FF }) {
                cont.resume("bn"); return@suspendCancellableCoroutine
            }
            val client = LanguageIdentification.getClient()
            client.identifyLanguage(text)
                .addOnSuccessListener { tag -> cont.resume(tag ?: "und") }
                .addOnFailureListener { cont.resume("und") }
        }


    suspend fun translate(text: String, targetLang: String): String {
        if (text.isBlank()) return text
        val target = normaliseTag(targetLang) ?: return text
        val sourceTag = detectLanguage(text)
        val source = normaliseTag(sourceTag) ?: TranslateLanguage.ENGLISH
        if (source == target) return text
        return runCatching {
            val translator = getOrBuildTranslator(source, target)
            ensureModelDownloaded(translator)
            translateOnce(translator, text)
        }.onFailure {
            Log.w(TAG, "translate $sourceTag→$targetLang failed: ${it.message}")
        }.getOrDefault(text)
    }

    /**
     * Bulk variant: translates each entry to [targetLang] and returns the
     * results in the same order. Sentences that are already in the target
     * language are returned unchanged, no model call wasted.
     */
    suspend fun translateAll(
        items: List<String>,
        targetLang: String,
    ): List<String> = items.map { translate(it, targetLang) }

    /**
     * Pre-warms the Bengali↔English translator models. Safe to call from
     * the Application's onCreate so the first user tap on "Translate" is
     * instant on devices that have already cached the model. Failures are
     * silent — they just mean the first interactive translate will block
     * on the download.
     */
    fun warmUp(@Suppress("UNUSED_PARAMETER") context: Context) {
        runCatching {
            getOrBuildTranslator(TranslateLanguage.ENGLISH, TranslateLanguage.BENGALI)
            getOrBuildTranslator(TranslateLanguage.BENGALI, TranslateLanguage.ENGLISH)
        }
    }

    private fun getOrBuildTranslator(source: String, target: String): Translator {
        val key = source to target
        cache[key]?.let { return it }
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val t = Translation.getClient(opts)
        cache[key] = t
        return t
    }

    private suspend fun ensureModelDownloaded(translator: Translator) =
        suspendCancellableCoroutine { cont ->
            // We don't gate on Wi-Fi — voice notes are short text, the model
            // is a one-time ~30 MB download, and the user explicitly asked
            // for a translation. Better to take the cellular hit once than
            // make the feature look broken.
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resume(Unit)
                    Log.w(TAG, "model download failed: ${e.message}")
                }
        }

    private suspend fun translateOnce(translator: Translator, text: String): String =
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { out -> if (cont.isActive) cont.resume(out ?: text) }
                .addOnFailureListener { if (cont.isActive) cont.resume(text) }
        }

    /** Normalise common locale tags ("en-US", "bn-BD", "BN") to ML Kit's
     *  expected codes ("en", "bn"). Returns null for unsupported languages. */
    private fun normaliseTag(tag: String): String? {
        val base = tag.substringBefore('-').lowercase()
        return when (base) {
            "en" -> TranslateLanguage.ENGLISH
            "bn" -> TranslateLanguage.BENGALI
            else -> null
        }
    }
}


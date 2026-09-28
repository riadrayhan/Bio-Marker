package com.riad.bio_marker.ml

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.riad.bio_marker.BuildConfig
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.VoiceTranscriptStore
import com.riad.bio_marker.core.WindowSummaryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Sends the end-of-window [WindowSummaryStore.WindowSummary] to the Groq
 * Chat Completions API and parses the model's reply into:
 *   - **headline**       : one-sentence overall verdict for the dashboard,
 *   - **analysis**       : 2–4 sentences of context the user can read,
 *   - **recommendations**: short bullet list of concrete actions.
 *
 * Network call is plain `HttpURLConnection` to avoid pulling in OkHttp; the
 * worker that calls this already runs on `Dispatchers.IO` inside a
 * CoroutineWorker.
 *
 * Privacy: the request body contains *only* the aggregate summary numbers
 * (no raw audio, no faces, no lat/lon, no message content, no contacts).
 *
 * Auth: Bearer token from `BuildConfig.GROQ_API_KEY`, injected at build time
 * from `<project-root>/.env`. If the key is empty we skip the network call
 * and return an "AI disabled" placeholder so the caller can still persist a
 * usable summary.
 */
object GroqAgentClient {

    private const val TAG = "GroqAgentClient"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val TIMEOUT_MS = 25_000

    data class AiResult(
        val headline: String,
        val analysis: String,
        val recommendations: List<String>,
        val engine: String,
    )

    /**
     * Lightweight vitals-based relaxation advisor invoked from
     * [com.riad.bio_marker.ui.VitalsCheckInActivity] right after the user
     * provides a heart-rate / blood-pressure / breath-rate reading because
     * the [com.riad.bio_marker.core.StressDetector] flagged them as hyper.
     *
     * Returns a small, immediately-actionable plan — not a window-end review.
     * The reply uses the same locale rules as [analyze].
     */
    suspend fun suggestRelaxation(
        heartRateBpm: Int?,
        bpSystolic: Int?,
        bpDiastolic: Int?,
        breathPerMin: Int?,
        stressReasons: List<String> = emptyList(),
    ): AiResult? = withContext(Dispatchers.IO) {
        val key = BuildConfig.GROQ_API_KEY
        if (key.isBlank()) {
            Log.w(TAG, "GROQ_API_KEY empty — relax suggestion unavailable")
            return@withContext null
        }
        val model = BuildConfig.GROQ_MODEL.ifBlank { "llama-3.3-70b-versatile" }

        val appLocales = AppCompatDelegate.getApplicationLocales()
        val locale: Locale = when {
            !appLocales.isEmpty -> appLocales[0] ?: Locale.US
            else -> runCatching {
                ServiceLocator.appContext.resources.configuration.locales[0]
            }.getOrDefault(Locale.US)
        }
        val replyLanguage = when (locale.language) {
            "bn" -> "Bengali (বাংলা)"
            else -> locale.displayLanguage.ifBlank { "English" }
        }

        val vitals = JSONObject().apply {
            heartRateBpm?.let { put("heart_rate_bpm", it) }
            bpSystolic?.let { put("bp_systolic_mmhg", it) }
            bpDiastolic?.let { put("bp_diastolic_mmhg", it) }
            breathPerMin?.let { put("breath_rate_per_min", it) }
            if (stressReasons.isNotEmpty())
                put("stress_signals", JSONArray(stressReasons))
        }

        val system = """
            You are a kind, conversational wellbeing coach. The user just
            felt stressed and shared a quick vitals snapshot. Based ONLY on
            the values present, write a short, warm reply that:
              1. Names whether each value is high / normal / low — using
                 these adult resting references:
                   • heart rate 60–100 bpm normal, >100 elevated, >115 very high
                   • blood pressure 90–120 / 60–80 mmHg normal,
                     130/85 elevated, ≥140/90 high (concerning if persistent)
                   • breath rate 12–20 / min normal, >22 fast / shallow.
              2. Suggests 2–3 immediate calming actions, e.g.:
                   • 4-7-8 breathing for 2 minutes
                   • play a relaxing song / instrumental / nature sounds
                     (mention a genre, e.g. "soft piano" or
                     "instrumental raga / classical Bangla folk")
                   • drink water, step outside for 3 minutes,
                     unclench shoulders, do a slow neck stretch.
              3. Stays kind, never alarming, never a diagnosis.
              4. If a vital is extremely high (HR > 130, BP ≥ 160/100,
                 breath > 28 sustained), GENTLY recommend seeing a doctor
                 if the value persists, but do NOT panic the user.

            Reply in: $replyLanguage. Strict JSON shape:
            {
              "headline": "<≤ 10 words, e.g. 'Heart rate is high — let\u2019s slow it down'>",
              "analysis": "<2–3 sentences, references actual values>",
              "recommendations": ["<step 1>", "<step 2>", "<step 3>"]
            }
            No markdown, no commentary outside the JSON.
        """.trimIndent()

        val userMsg = "Vitals just measured: $vitals\n\n" +
            "Please return the JSON above with a calming, evidence-based plan."

        val req = JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", userMsg))
            })
        }
        Log.i(TAG, "Calling Groq for relax suggestion bodyLen=${req.length()}")
        var raw = postJson(key, req.toString())
        if (raw == null) {
            delay(800)
            raw = postJson(key, req.toString())
        }
        raw ?: return@withContext null
        parseResponse(raw, model)
    }

    suspend fun analyze(summary: WindowSummaryStore.WindowSummary): AiResult? =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.GROQ_API_KEY
            if (key.isBlank()) {
                Log.w(TAG, "GROQ_API_KEY is empty — skipping AI call. " +
                    "Set it in <project-root>/.env and rebuild.")
                return@withContext null
            }
            val model = BuildConfig.GROQ_MODEL.ifBlank { "llama-3.3-70b-versatile" }
            val body = buildRequestBody(summary, model)
            Log.i(TAG, "Calling Groq model='$model' bodyLen=${body.length}")
            // One retry on transient 5xx / network failures so a flaky cell
            // tower doesn't permanently leave the user with a generic
            // "Window analysis" headline.
            var raw = postJson(key, body)
            if (raw == null) {
                delay(800)
                Log.i(TAG, "Retrying Groq call once after transient failure")
                raw = postJson(key, body)
            }
            raw ?: return@withContext null
            parseResponse(raw, model)
    }

    /**
     * Per-utterance transcript timeline analyser. Given the full set of
     * voice transcripts collected during a monitoring window (timestamps +
     * raw text + lexicon-based sentiment), Groq returns a fine-grained
     * verdict like *"good mood for ~50 minutes, angry for the last ~10
     * minutes"* — not just an overall aggregate.
     *
     * Privacy: this is the **only** Groq call that ships raw transcript
     * text. The user can clear or disable the transcript log from the
     * Voice notes screen at any time.
     */
    suspend fun analyzeTranscripts(
        entries: List<VoiceTranscriptStore.Entry>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): AiResult? = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext null
        val key = BuildConfig.GROQ_API_KEY
        if (key.isBlank()) {
            Log.w(TAG, "GROQ_API_KEY empty — transcript analysis unavailable")
            return@withContext null
        }
        val model = BuildConfig.GROQ_MODEL.ifBlank { "llama-3.3-70b-versatile" }
        val replyLanguage = pickReplyLanguage()

        // Build a compact, ordered timeline. Use minute-offset from window
        // start so the model thinks in "minute 0–60" terms instead of raw
        // epoch ms (smaller payload, easier reasoning, no PII leakage).
        val sorted = entries.sortedBy { it.timestampMs }
        val baseMs = if (windowStartMs > 0L) windowStartMs else sorted.first().timestampMs
        val timeline = JSONArray()
        for (e in sorted) {
            val minutes = ((e.timestampMs - baseMs).coerceAtLeast(0L) / 60_000L).toInt()
            timeline.put(JSONObject().apply {
                put("t_min", minutes)
                put("text", e.text)
                put("lang", e.lang)
                put("source", e.source) // "voice" (spoken) or "keyboard" (typed)
                put("words", e.wordCount)
                put("sentiment", e.sentimentScore.toDouble())
                put("mood", e.mood)
            })
        }
        val totalMinutes = if (windowEndMs > windowStartMs) {
            ((windowEndMs - windowStartMs) / 60_000L).toInt()
        } else {
            ((sorted.last().timestampMs - baseMs) / 60_000L).toInt() + 1
        }

        val system = """
            You are a kind, evidence-grounded mood analyst. The user enabled a
            monitoring window of about $totalMinutes minutes. During the window
            we collected two kinds of the user's own words:
              • SPOKEN words (`source` = "voice") transcribed by on-device
                speech recognition, and
              • TYPED words (`source` = "keyboard") captured from what the user
                wrote on the in-app keyboard.
            Both are the user's real words (English + Bengali mixed). You receive
            one ORDERED timeline combining them; each entry has `t_min` (minutes
            elapsed since the window began), `text` (raw utterance/message, may be
            in Bengali script), `source` (voice or keyboard), `mood` (lexicon
            classification: positive / neutral / negative) and a `sentiment`
            score in [-1, 1].

            Your job — make ONE overall decision about how the user's mood was,
            weighing BOTH what they said and what they typed:
              1. Produce a fine-grained mood timeline of the form:
                   "you were in a good mood for ~50 minutes, then in the last
                    ~10 minutes you sounded angry / sad / anxious".
                 Reference actual minute ranges from the data — don't invent.
              2. If the timeline shows a clear shift (positive → negative or
                 vice versa), name the approximate minute it happened.
              3. If spoken and typed moods disagree, mention it briefly and lean
                 on the overall balance rather than a single message.
              4. Be empathetic. If the user appears upset toward the end,
                 close with one or two concrete coping suggestions
                 (deep breathing, talking to someone, a short walk, music).
              5. Do NOT diagnose. Do NOT moralise. Do NOT mention specific
                 lexicon words by name — speak naturally, as a friend would.
              6. If only a few entries exist (< 3), say so honestly and
                 give a single soft observation instead of inventing a timeline.

            Reply in: $replyLanguage. The JSON keys stay in English; the
            *values* must be in $replyLanguage (use the appropriate script,
            e.g. বাংলা).

            Strict JSON shape:
            {
              "headline": "<one short sentence — overall mood verdict>",
              "analysis": "<2 to 5 sentences, the actual minute-level timeline>",
              "recommendations": ["<bullet 1>", "<bullet 2>"]
            }
            No markdown, no commentary outside the JSON.
        """.trimIndent()

        val userMsg = "Window length: $totalMinutes minutes. " +
            "Utterance count: ${sorted.size}.\n" +
            "Timeline (chronological): $timeline\n\n" +
            "Please return the JSON above."

        val req = JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", userMsg))
            })
        }
        Log.i(TAG, "Calling Groq for transcript timeline (${sorted.size} entries, $totalMinutes min)")
        var raw = postJson(key, req.toString())
        if (raw == null) {
            delay(800)
            raw = postJson(key, req.toString())
        }
        raw ?: return@withContext null
        parseResponse(raw, model)
    }

    private fun pickReplyLanguage(): String {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val locale: Locale = when {
            !appLocales.isEmpty -> appLocales[0] ?: Locale.US
            else -> runCatching {
                ServiceLocator.appContext.resources.configuration.locales[0]
            }.getOrDefault(Locale.US)
        }
        return when (locale.language) {
            "bn" -> "Bengali (বাংলা)"
            else -> locale.displayLanguage.ifBlank { "English" }
        }
    }

    private fun buildRequestBody(s: WindowSummaryStore.WindowSummary, model: String): String {
        // Reply in the user's UI language. Honour the per-app language
        // chosen from Settings (AppCompatDelegate) before falling back to
        // the system locale — otherwise a user who picked বাংলা in our
        // in-app switcher would still get an English reply because the
        // background worker context doesn't carry the AppCompat locale.
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val locale: Locale = when {
            !appLocales.isEmpty -> appLocales[0] ?: Locale.US
            else -> runCatching {
                ServiceLocator.appContext.resources.configuration.locales[0]
            }.getOrDefault(Locale.US)
        }
        val replyLanguage = when (locale.language) {
            "bn" -> "Bengali (বাংলা)"
            else -> locale.displayLanguage.ifBlank { "English" }
        }

        // Only send fields that were actually measured. A flood of zeros
        // makes the model think the user did nothing and then it lectures
        // them about it. Each field has its own "is this present?" rule
        // so we don't include 0 sleep, 0 steps, or null heart rate.
        val metrics = JSONObject().apply {
            put("period_hours", s.periodHours)
            if (s.totalSteps > 0 || s.walkingMinutes > 0) {
                put("walking_minutes", s.walkingMinutes)
                put("walking_bouts", s.walkingBouts)
                put("total_steps", s.totalSteps)
            }
            if (s.locationFixes > 0) {
                put("location_changes", s.locationFixes)
                put("location_entropy", s.locationEntropy)
                put("location_radius_km", s.radiusKm)
            }
            s.heartRateAvg?.let { put("heart_rate_avg_bpm", it) }
            s.heartRateMin?.let { put("heart_rate_min_bpm", it) }
            s.heartRateMax?.let { put("heart_rate_max_bpm", it) }
            if (s.moodSampleCount > 0) {
                put("dominant_face_mood", s.moodLabel)
                put("negative_mood_share", s.moodNegativeShare)
                put("face_sample_count", s.moodSampleCount)
            }
            if (s.sleepHours > 0f) put("sleep_hours", s.sleepHours)
            if (s.motionAnomalies > 0) put("motion_anomalies", s.motionAnomalies)
            s.voiceSentiment?.let { put("voice_sentiment_score", it) }
            if (s.talkingMinutes > 0) put("talking_minutes", s.talkingMinutes)
            if (s.callMinutes > 0 || s.callCount > 0) {
                put("call_minutes", s.callMinutes)
                put("call_count", s.callCount)
            }
            if (s.totalPhoneUseMinutes > 0) {
                put("total_phone_use_minutes", s.totalPhoneUseMinutes)
                put("social_media_minutes", s.socialMediaMinutes)
                put("video_minutes", s.videoMinutes)
                put("social_app_ratio", s.socialAppRatio)
            }
            // Routine quality (sleep/wake/bedtime in user local time)
            if (s.routineScore in 0..100) put("routine_score_0_100", s.routineScore)
            s.wakeTimeLocal?.let { put("wake_time_local", it) }
            s.bedTimeLocal?.let { put("bed_time_local", it) }
            s.routineFeedback?.let { put("routine_feedback", it) }
            // Blood pressure (manual entry only — null on phones with no BP cuff)
            s.bpSystolic?.let { put("bp_systolic_mmhg", it) }
            s.bpDiastolic?.let { put("bp_diastolic_mmhg", it) }
            // Per-point timeline (last ~30 short, non-content labels with hh:mm prefix)
            if (s.actionPointCount > 0) put("action_point_count", s.actionPointCount)
            if (s.topActionPoints.isNotEmpty())
                put("recent_action_points", JSONArray(s.topActionPoints))
        }
        // Tells the model exactly which fields *were* measured and which
        // were missing, so it doesn't fabricate verdicts about absent data.
        val measuredKeys = metrics.keys().asSequence().toList()
        val measuredList = measuredKeys.joinToString(", ")

        val system = """
            You are a privacy-respecting wellbeing assistant for a smartphone
            app called "Bio Marker". You receive ONLY aggregated, non-
            identifying metrics from a single user's monitoring window.

            Critical rules about the data you receive:
              • Fields NOT present in the JSON were NOT measured during this
                window. Do NOT assume they are zero, do NOT lecture the user
                about them, do NOT recommend hitting a target for an unmeasured
                field. Just stay silent on missing data.
              • Only the fields listed here were measured: [$measuredList].
              • If almost nothing was measured, say so honestly in one sentence
                and suggest enabling sensors / IME / camera in Settings —
                don't invent a verdict.

            When data IS present, your job is:
              1. Diagnose how the past ${'$'}{s.periodHours}-hour window went,
                 referencing evidence-based healthy norms only when the
                 relevant field is present (sleep 7–9 h, walking ≥ 30 min,
                 phone use ≤ 5 h, talking 15–90 min, calm mood,
                 wake-up 05:30–07:30 local, bedtime 21:30–23:00 local,
                 BP < 120/80 = ideal).
              2. If `wake_time_local` is later than 09:00 OR `bed_time_local`
                 is after 00:00, gently and warmly suggest moving the routine
                 earlier, referencing the healthy targets above. Use the
                 `routine_feedback` line as a starting hint.
              3. If `recent_action_points` is present, mention 1–2 concrete
                 things the user actually did (e.g. "you took a 12-min walk
                 at 17:30") so the analysis feels personalised rather than
                 generic.
              4. Suggest 2–3 specific, actionable next-day targets — but
                 ONLY for metrics that were actually measured. Do not invent
                 step / sleep / call targets if those fields are missing.
              5. Be kind, short, evidence-grounded. No diagnosis, no medical
                 claims, no scary language.

            Reply in: $replyLanguage. The JSON keys themselves stay in English,
            but the *values* (headline, analysis, recommendations) must be in
            $replyLanguage. Use the appropriate script (e.g. বাংলা).

            Respond with strict JSON of the form:
            {
              "headline": "<one short sentence overall verdict>",
              "analysis": "<2 to 4 sentences explaining what stood out>",
              "recommendations": ["<bullet 1>", "<bullet 2>", "<bullet 3>"]
            }
            No markdown, no commentary outside the JSON.
        """.trimIndent()

        val userMsg = "Window summary (only measured fields shown): $metrics\n\n" +
            "Please analyse it and respond with the JSON shape above. " +
            "Remember: stay silent on fields not in this JSON."

        val req = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", userMsg))
            })
        }
        return req.toString()
    }

    private fun postJson(bearer: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $bearer")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            if (code !in 200..299) {
                // 400-class errors are usually our fault (bad model id, bad
                // payload) — log the body so the developer can see why.
                // 5xx is treated as transient and the caller will retry.
                Log.w(TAG, "Groq HTTP $code — ${text?.take(500)}")
                return null
            }
            Log.i(TAG, "Groq HTTP 200 (${text?.length ?: 0} chars)")
            text
        } catch (t: Throwable) {
            Log.e(TAG, "Groq request failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * Groq returns OpenAI-shape:
     *   { choices: [{ message: { content: "<json>" } }], … }
     * We then parse `content` as our own JSON shape.
     */
    private fun parseResponse(raw: String, model: String): AiResult? {
        return try {
            val root = JSONObject(raw)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
            if (content.isNullOrBlank()) {
                Log.w(TAG, "Groq response had no choices[0].message.content. Raw=${raw.take(400)}")
                return null
            }
            val inner = JSONObject(content)
            val recs = mutableListOf<String>()
            inner.optJSONArray("recommendations")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotEmpty()) recs.add(v)
                }
            }
            val headline = inner.optString("headline").trim()
            val analysis = inner.optString("analysis").trim()
            if (headline.isBlank() && analysis.isBlank() && recs.isEmpty()) {
                Log.w(TAG, "Groq parsed JSON had every field empty. Inner=${content.take(400)}")
                return null
            }
            AiResult(
                headline = headline.ifBlank { "Window analysis" },
                analysis = analysis,
                recommendations = recs,
                engine = "groq:$model",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Groq response: ${t.message}. Raw=${raw.take(400)}", t)
            null
        }
    }
}

package com.riad.bio_marker.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.TextTranslator
import com.riad.bio_marker.core.VoiceTranscriptStore
import com.riad.bio_marker.ml.GroqAgentClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/**
 * Tools-tab screen that shows every voice utterance the app captured
 * (Bangla + English) during the current monitoring window, plus the
 * latest cached Groq AI mood-timeline verdict.
 *
 * The user can:
 *   - read each utterance with timestamp + per-utterance mood chip,
 *   - tap "Analyse with AI now" to call Groq on demand (also fires
 *     automatically at window-end via [com.riad.bio_marker.worker.MoodWindowEndWorker]),
 *   - clear the log to delete all stored transcripts.
 */
class VoiceNotesActivity : AppCompatActivity() {

    private lateinit var listColumn: LinearLayout
    private lateinit var verdictBox: LinearLayout
    private lateinit var verdictTitle: TextView
    private lateinit var verdictBody: TextView
    private lateinit var verdictRecs: TextView
    private lateinit var verdictMeta: TextView
    private lateinit var statusLine: TextView
    private lateinit var aiBtn: Button
    private lateinit var translateBtn: Button
    private lateinit var spinner: ProgressBar

    /** When true, every transcript + the AI verdict are shown translated
     *  into [translationTarget]. Toggled by the Translate button. */
    private var translationActive: Boolean = false
    /** Target language tag for the active translation ("en" or "bn"). */
    private var translationTarget: String = "en"
    /** Cache of `original→translated` strings for the current target so
     *  re-rendering doesn't re-invoke the model. Cleared when the toggle
     *  flips off or the target changes. */
    private val translationCache = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.voice_notes_title)

        val pad = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor(CREAM_BG))
        }

        // Header.
        root.addView(TextView(this).apply {
            text = getString(R.string.voice_notes_header)
            setTextColor(Color.parseColor(CREAM_TEXT))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.voice_notes_subhead)
            setTextColor(Color.parseColor(CREAM_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(12))
        })

        // Status line ("Showing 12 utterances from current 1-hour window").
        statusLine = TextView(this).apply {
            setTextColor(Color.parseColor(CREAM_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(statusLine)

        // AI verdict card (hidden until we have a verdict).
        verdictBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCard(Color.parseColor(CREAM_ADVICE_BG), dp(18))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        verdictTitle = TextView(this).apply {
            setTextColor(Color.parseColor(CREAM_ACCENT))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        verdictBody = TextView(this).apply {
            setTextColor(Color.parseColor(CREAM_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, 0)
        }
        verdictRecs = TextView(this).apply {
            setTextColor(Color.parseColor(CREAM_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
        }
        verdictMeta = TextView(this).apply {
            setTextColor(Color.parseColor(CREAM_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(6), 0, 0)
        }
        verdictBox.addView(verdictTitle)
        verdictBox.addView(verdictBody)
        verdictBox.addView(verdictRecs)
        verdictBox.addView(verdictMeta)
        root.addView(verdictBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) })

        // ----- Actions -----
        // Primary: "Analyse with AI" full-width with an inline spinner. The
        // old single-row layout packed three buttons horizontally; the long
        // AI label pushed Translate/Clear off the screen edge so they couldn't
        // be tapped — that was the "buttons not responsive" bug.
        aiBtn = Button(this).apply {
            text = getString(R.string.voice_notes_run_ai)
            isAllCaps = false
            setOnClickListener { runAiAnalysis() }
        }
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        val aiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        aiRow.addView(aiBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        aiRow.addView(spinner, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            leftMargin = dp(10)
        })
        root.addView(aiRow)

        // Secondary: Translate + Clear share the row equally so neither can
        // overflow off-screen.
        translateBtn = Button(this).apply {
            text = translateButtonLabelForTarget()
            isAllCaps = false
            setOnClickListener { toggleTranslate() }
        }
        val clearBtn = Button(this).apply {
            text = getString(R.string.voice_notes_clear)
            isAllCaps = false
            setOnClickListener { confirmClear() }
        }
        val secRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        secRow.addView(translateBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        secRow.addView(clearBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        root.addView(secRow)

        // Section header for the list.
        root.addView(TextView(this).apply {
            text = getString(R.string.voice_notes_list_header)
            setTextColor(Color.parseColor(CREAM_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setAllCaps(true)
            setPadding(0, dp(4), 0, dp(8))
        })

        // List column (we'll populate it in onResume).
        listColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listColumn)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor(CREAM_BG))
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * The "current window" is preferred. If none is active, we fall back
     * to the most recent window we have a transcript for so the user can
     * still review past utterances.
     */
    private fun activeWindowStartMs(): Long {
        val current = ServiceLocator.monitoringStore.current()?.startedAtMs
        if (current != null && current > 0L) return current
        val all = ServiceLocator.voiceTranscriptStore.allEntries()
        return all.firstOrNull()?.windowStartMs ?: 0L
    }

    private fun refresh() {
        val windowStart = activeWindowStartMs()
        val entries = if (windowStart > 0L) {
            ServiceLocator.voiceTranscriptStore.entriesFor(windowStart)
        } else {
            ServiceLocator.voiceTranscriptStore.allEntries()
        }

        statusLine.text = if (entries.isEmpty()) {
            getString(R.string.voice_notes_empty_status)
        } else {
            getString(R.string.voice_notes_status_count, entries.size)
        }

        // Render verdict if we have one for this window.
        val verdict = ServiceLocator.voiceTranscriptStore.loadVerdict()
        if (verdict != null && verdict.windowStartMs == windowStart &&
            verdict.headline.isNotBlank()
        ) {
            verdictBox.visibility = View.VISIBLE
            verdictTitle.text = maybeTranslated(verdict.headline)
            verdictBody.text = maybeTranslated(verdict.analysis)
            verdictRecs.text = if (verdict.recommendations.isEmpty()) "" else
                verdict.recommendations.joinToString("\n") {
                    "• " + maybeTranslated(it)
                }
            verdictRecs.visibility = if (verdict.recommendations.isEmpty()) View.GONE else View.VISIBLE
            val ts = DateFormat.getTimeFormat(this).format(Date(verdict.createdAtMs))
            verdictMeta.text = getString(R.string.voice_notes_verdict_meta, ts, verdict.engine)
        } else {
            verdictBox.visibility = View.GONE
        }

        // Always tappable so the user gets feedback; runAiAnalysis() shows a
        // toast when there are no entries yet rather than looking "dead".
        aiBtn.isEnabled = true

        // Render list (newest first).
        listColumn.removeAllViews()
        if (entries.isEmpty()) {
            listColumn.addView(TextView(this).apply {
                text = getString(R.string.voice_notes_empty_hint)
                setTextColor(Color.parseColor(CREAM_MUTED))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val timeFmt = DateFormat.getTimeFormat(this)
        for (e in entries.sortedByDescending { it.timestampMs }) {
            listColumn.addView(buildEntryRow(e, timeFmt))
        }
    }

    private fun buildEntryRow(
        e: VoiceTranscriptStore.Entry,
        timeFmt: java.text.DateFormat,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedCard(Color.parseColor(CREAM_CARD), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(TextView(this).apply {
            text = timeFmt.format(Date(e.timestampMs))
            setTextColor(Color.parseColor(CREAM_TEXT))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        header.addView(TextView(this).apply {
            val srcLabel = if (e.source == "keyboard")
                getString(R.string.voice_notes_src_typed)
            else getString(R.string.voice_notes_src_spoken)
            text = " · $srcLabel · ${e.wordCount} ${getString(R.string.voice_notes_words)}"
            setTextColor(Color.parseColor(CREAM_MUTED))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        header.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        header.addView(buildMoodChip(e.mood))
        row.addView(header)
        row.addView(TextView(this).apply {
            text = maybeTranslated(e.text)
            setTextColor(Color.parseColor(CREAM_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(4), 0, 0)
        })
        val outerLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = outerLp
            addView(row)
        }
    }

    private fun buildMoodChip(mood: String): View {
        val (label, color) = when (mood) {
            "positive" -> getString(R.string.voice_notes_mood_pos) to "#1B7F3B"
            "negative" -> getString(R.string.voice_notes_mood_neg) to "#B71C1C"
            else       -> getString(R.string.voice_notes_mood_neutral) to "#555555"
        }
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            background = roundedCard(Color.parseColor(color), dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
    }

    private fun runAiAnalysis() {
        val windowStart = activeWindowStartMs()
        val entries = if (windowStart > 0L) {
            ServiceLocator.voiceTranscriptStore.entriesFor(windowStart)
        } else {
            ServiceLocator.voiceTranscriptStore.allEntries()
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.voice_notes_empty_hint, Toast.LENGTH_SHORT).show()
            return
        }
        aiBtn.isEnabled = false
        spinner.visibility = View.VISIBLE
        val session = ServiceLocator.monitoringStore.current()
        val startMs = windowStart.takeIf { it > 0L } ?: entries.minOf { it.timestampMs }
        val endMs = session?.endsAtMs ?: System.currentTimeMillis()
        lifecycleScope.launch {
            val ai = withContext(Dispatchers.IO) {
                GroqAgentClient.analyzeTranscripts(
                    entries = entries,
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                )
            }
            spinner.visibility = View.GONE
            aiBtn.isEnabled = true
            if (ai == null) {
                Toast.makeText(this@VoiceNotesActivity,
                    R.string.voice_notes_ai_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            ServiceLocator.voiceTranscriptStore.saveVerdict(
                VoiceTranscriptStore.AiVerdict(
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    createdAtMs = System.currentTimeMillis(),
                    headline = ai.headline,
                    analysis = ai.analysis,
                    recommendations = ai.recommendations,
                    engine = ai.engine,
                )
            )
            refresh()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_notes_clear_title)
            .setMessage(R.string.voice_notes_clear_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.voice_notes_clear) { _, _ ->
                ServiceLocator.voiceTranscriptStore.clearAll()
                refresh()
            }
            .show()
    }

    // ------------------------- TRANSLATION -------------------------

    /**
     * Returns the BCP-47 target language for translation = the *opposite*
     * of the user's current UI locale. Bangla user → translate to English,
     * English user → translate to Bangla. This makes the button useful
     * regardless of which language the user prefers.
     */
    private fun pickTranslationTarget(): String {
        val ui = Locale.getDefault().language.lowercase()
        return if (ui.startsWith("bn")) "en" else "bn"
    }

    private fun translateButtonLabelForTarget(): String {
        if (translationActive) return getString(R.string.translate_show_original)
        return if (translationTarget == "bn")
            getString(R.string.translate_to_bn)
        else getString(R.string.translate_to_en)
    }

    private fun toggleTranslate() {
        if (translationActive) {
            // Flip OFF — restore originals.
            translationActive = false
            translationCache.clear()
            translateBtn.text = translateButtonLabelForTarget()
            refresh()
            return
        }
        translationTarget = pickTranslationTarget()
        translationActive = true
        translateBtn.isEnabled = false
        translateBtn.text = getString(R.string.translate_in_progress)
        spinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = runCatching { precomputeTranslations() }.isSuccess
            spinner.visibility = View.GONE
            translateBtn.isEnabled = true
            if (!ok) {
                translationActive = false
                translationCache.clear()
                Toast.makeText(
                    this@VoiceNotesActivity,
                    R.string.translate_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
            translateBtn.text = translateButtonLabelForTarget()
            refresh()
        }
    }

    /** Run every visible piece of text through [TextTranslator] once and
     *  populate [translationCache] so [refresh] can render synchronously. */
    private suspend fun precomputeTranslations() {
        val windowStart = activeWindowStartMs()
        val entries = if (windowStart > 0L) {
            ServiceLocator.voiceTranscriptStore.entriesFor(windowStart)
        } else {
            ServiceLocator.voiceTranscriptStore.allEntries()
        }
        val verdict = ServiceLocator.voiceTranscriptStore.loadVerdict()
        val toTranslate = mutableSetOf<String>()
        entries.forEach { if (it.text.isNotBlank()) toTranslate += it.text }
        if (verdict != null) {
            if (verdict.headline.isNotBlank()) toTranslate += verdict.headline
            if (verdict.analysis.isNotBlank()) toTranslate += verdict.analysis
            verdict.recommendations.forEach { if (it.isNotBlank()) toTranslate += it }
        }
        withContext(Dispatchers.Default) {
            for (orig in toTranslate) {
                if (translationCache.containsKey(orig)) continue
                translationCache[orig] = TextTranslator.translate(orig, translationTarget)
            }
        }
    }

    /** Returns the translated form when [translationActive], else the
     *  original. Safe to call from the UI thread — only hits the cache. */
    private fun maybeTranslated(original: String): String {
        if (!translationActive) return original
        return translationCache[original] ?: original
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /** Builds a solid rounded-rectangle background at runtime so the
     *  programmatic Voice-notes UI matches the app's cream card style
     *  without needing a separate drawable per colour. */
    private fun roundedCard(color: Int, radiusPx: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private companion object {
        // Cream / amber palette — mirrors res/values/colors.xml so this
        // code-built screen stays visually consistent with the Dashboard.
        const val CREAM_BG = "#F8F4EC"
        const val CREAM_CARD = "#FFFCF6"
        const val CREAM_ADVICE_BG = "#F4ECDC"
        const val CREAM_TEXT = "#3A2F1B"
        const val CREAM_MUTED = "#7A6D55"
        const val CREAM_ACCENT = "#9C6B2F"
    }
}


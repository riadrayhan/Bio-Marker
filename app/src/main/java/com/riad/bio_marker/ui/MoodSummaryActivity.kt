package com.riad.bio_marker.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.ml.MoodSummarizer
import com.riad.bio_marker.worker.MoodLabelStrings
import java.text.DateFormat
import java.util.Date

/**
 * Shown when a monitoring window expires. Reads the frozen mood verdict
 * persisted by [com.riad.bio_marker.worker.MoodWindowEndWorker] and renders
 * a final mood readout for the user.
 *
 * Tapping "Got it" marks the verdict as acknowledged so we don't keep
 * re-prompting the user every time they open the app.
 */
class MoodSummaryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mood_summary)

        val frozen = ServiceLocator.moodVerdictStore.load()
        val titleView = findViewById<TextView>(R.id.moodTitle)
        val labelView = findViewById<TextView>(R.id.moodLabel)
        val adviceView = findViewById<TextView>(R.id.moodAdvice)
        val breakdownTitle = findViewById<TextView>(R.id.moodBreakdownTitle)
        val breakdownTable = findViewById<TableLayout>(R.id.moodBreakdownTable)
        val transitionsView = findViewById<TextView>(R.id.moodTransitions)
        val hrView = findViewById<TextView>(R.id.moodHeartRate)
        val statsView = findViewById<TextView>(R.id.moodStats)
        val rangeView = findViewById<TextView>(R.id.moodRange)
        val ackBtn = findViewById<Button>(R.id.moodAckBtn)

        // The Groq voice-transcript verdict — the primary "how was your mood"
        // result now that face-based mood capture has been removed. Populated
        // by MoodWindowEndWorker at window-end (or on-demand from Voice notes).
        val aiVerdict = ServiceLocator.voiceTranscriptStore.loadVerdict()
            ?.takeIf { it.headline.isNotBlank() || it.analysis.isNotBlank() }
        renderAiVerdict(aiVerdict)

        if (frozen == null) {
            titleView.setText(R.string.mood_summary_title)
            // When the AI verdict is present we let it carry the headline; the
            // legacy face label/advice would just read a misleading "Neutral".
            if (aiVerdict != null) {
                labelView.visibility = View.GONE
                adviceView.visibility = View.GONE
            } else {
                labelView.visibility = View.VISIBLE
                adviceView.visibility = View.VISIBLE
                labelView.setText(R.string.mood_label_neutral)
                adviceView.setText(R.string.mood_summary_empty)
            }
            statsView.text = ""
            rangeView.text = ""
            breakdownTitle.visibility = View.GONE
            breakdownTable.visibility = View.GONE
            transitionsView.visibility = View.GONE
            hrView.visibility = View.GONE
        } else {
            titleView.setText(R.string.mood_summary_title)
            labelView.setText(MoodLabelStrings.titleRes(frozen.dominantLabel))
            adviceView.setText(MoodLabelStrings.adviceRes(frozen.dominantLabel))
            statsView.text = getString(
                R.string.mood_summary_stats,
                frozen.sampleCount,
                (frozen.negativeShare * 100f).toInt(),
            )
            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            rangeView.text = getString(
                R.string.mood_summary_range,
                df.format(Date(frozen.windowStartMs)),
                df.format(Date(frozen.windowEndMs)),
            )

            // Per-mood time breakdown.
            populateBreakdown(breakdownTable, frozen.durationsMs)
            if (breakdownTable.childCount == 0) {
                breakdownTitle.visibility = View.GONE
                breakdownTable.visibility = View.GONE
            }

            transitionsView.text = getString(R.string.mood_transitions, frozen.transitions)

            val hr = frozen.heartRateAvg
            if (hr != null && hr > 30f) {
                hrView.visibility = View.VISIBLE
                hrView.text = getString(R.string.mood_hr_avg, hr.toInt())
            } else {
                hrView.visibility = View.GONE
            }
        }

        ackBtn.setOnClickListener {
            ServiceLocator.moodVerdictStore.acknowledge()
            ServiceLocator.voiceTranscriptStore.acknowledgeVerdict()
            finish()
        }
    }

    /**
     * Renders the Groq voice-transcript verdict card. This is what answers
     * the user's "how was my mood" question from the words they actually
     * spoke during the window. Hidden entirely when no verdict exists.
     */
    private fun renderAiVerdict(v: com.riad.bio_marker.core.VoiceTranscriptStore.AiVerdict?) {
        val box = findViewById<View>(R.id.aiVerdictBox)
        if (v == null) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        findViewById<TextView>(R.id.aiVerdictHeadline).text = v.headline
        findViewById<TextView>(R.id.aiVerdictBody).text = v.analysis
        val recsView = findViewById<TextView>(R.id.aiVerdictRecs)
        if (v.recommendations.isEmpty()) {
            recsView.visibility = View.GONE
        } else {
            recsView.visibility = View.VISIBLE
            recsView.text = v.recommendations.joinToString("\n") { "• $it" }
        }
        val ts = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(v.createdAtMs))
        findViewById<TextView>(R.id.aiVerdictMeta).text =
            getString(R.string.mood_summary_ai_meta, ts, v.engine)
    }

    private fun populateBreakdown(table: TableLayout, durationsMs: Map<String, Long>) {
        table.removeAllViews()
        val total = durationsMs.values.sum().coerceAtLeast(1L)
        // Sort labels by duration descending; skip zero entries.
        val rows = MoodSummarizer.Label.values()
            .map { it to (durationsMs[it.name] ?: 0L) }
            .filter { it.second > 0L }
            .sortedByDescending { it.second }
        for ((label, ms) in rows) {
            val row = TableRow(this)
            row.addView(makeCell(getString(MoodLabelStrings.titleRes(label))))
            row.addView(makeCell(formatDuration(ms)))
            val pct = ((ms.toDouble() / total) * 100.0).toInt()
            row.addView(makeCell("$pct%"))
            table.addView(row)
        }
    }

    private fun makeCell(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setPadding(8, 6, 8, 6)
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@MoodSummaryActivity, R.color.cream_text))
            gravity = Gravity.START
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }
}


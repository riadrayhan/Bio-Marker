package com.riad.bio_marker.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riad.bio_marker.R
import com.riad.bio_marker.core.AppCategoryMap
import com.riad.bio_marker.core.DailyEvent
import com.riad.bio_marker.core.DailyReportStore
import com.riad.bio_marker.core.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chronological timeline of [DailyEvent]s for today (or yesterday if today
 * has no data yet) plus the [DailyReportStore.Report] recommendations.
 *
 * Each row shows: time range, icon by event type, duration, mood chip if any,
 * and the per-event narrator note.
 */
class TimelineFragment : Fragment(R.layout.fragment_timeline) {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render(view)
                    delay(15_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { render(it) }
    }

    private fun render(view: View) {
        val ctx = requireContext()
        val eventStore = ServiceLocator.dailyEventStore
        val reportStore = ServiceLocator.dailyReportStore

        val todayKey = eventStore.todayKey()
        val yKey = eventStore.yesterdayKey()
        val (date, events, report) = run {
            val tEv = eventStore.load(todayKey)
            if (tEv.isNotEmpty()) Triple(todayKey, tEv, reportStore.load(todayKey))
            else Triple(yKey, eventStore.load(yKey), reportStore.load(yKey))
        }

        view.findViewById<TextView>(R.id.tlDate).text = date
        view.findViewById<TextView>(R.id.tlHeadline).text =
            report?.headline ?: ctx.getString(R.string.timeline_title)

        // ---- AI window-end verdict (most-recent monitoring window) ----
        renderAiCard(view)

        val empty = view.findViewById<TextView>(R.id.tlEmpty)
        val eventsContainer = view.findViewById<LinearLayout>(R.id.tlEventsContainer)
        val recsContainer = view.findViewById<LinearLayout>(R.id.tlRecsContainer)
        eventsContainer.removeAllViews()
        recsContainer.removeAllViews()

        // ---- Recommendations ----
        val recs = report?.recommendations.orEmpty()
        if (recs.isEmpty()) {
            recsContainer.addView(plainText(
                ctx,
                ctx.getString(R.string.timeline_recs_empty),
                small = true,
            ))
        } else {
            for (r in recs) recsContainer.addView(recCard(ctx, r))
        }

        // ---- Events ----
        if (events.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        for (ev in events) eventsContainer.addView(eventCard(ctx, ev, report))
    }

    private fun eventCard(
        ctx: android.content.Context,
        ev: DailyEvent,
        report: DailyReportStore.Report?,
    ): View {
        val density = ctx.resources.displayMetrics.density
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (10 * density).toInt(),
                (14 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_card))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() }
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = "${iconFor(ev.type)} ${titleFor(ev)}"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        ev.moodLabel?.let { mood ->
            titleRow.addView(TextView(ctx).apply {
                text = mood
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.white))
                setBackgroundColor(ContextCompat.getColor(ctx, moodColor(mood)))
                setPadding(
                    (10 * density).toInt(), (3 * density).toInt(),
                    (10 * density).toInt(), (3 * density).toInt(),
                )
            })
        }
        card.addView(titleRow)

        // Time + duration line
        val start = timeFmt.format(Date(ev.startMs))
        val end = timeFmt.format(Date(ev.endMs))
        card.addView(TextView(ctx).apply {
            text = "$start–$end · ${ev.durationMin} min"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (2 * density).toInt()
        })

        // Narrator note
        val note = report?.perEventNotes?.firstOrNull { it.eventId == ev.id }?.note
        if (!note.isNullOrBlank()) {
            card.addView(TextView(ctx).apply {
                text = note
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (4 * density).toInt()
            })
        }
        return card
    }

    private fun recCard(ctx: android.content.Context, text: String): View {
        val density = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            this.text = "• $text"
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt(),
            )
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_card))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (6 * density).toInt()
            layoutParams = lp
        }
    }

    private fun plainText(
        ctx: android.content.Context,
        text: String,
        small: Boolean = false,
    ): View = TextView(ctx).apply {
        this.text = text
        textSize = if (small) 13f else 14f
        setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
    }

    private fun titleFor(ev: DailyEvent): String = when (ev.type) {
        DailyEvent.Type.WALK -> "Walk (${ev.metadata["steps"] ?: "?"} steps)"
        DailyEvent.Type.CALL -> "Phone call"
        DailyEvent.Type.APP_USE -> ev.metadata["app_name"]
            ?: AppCategoryMap.displayName(ev.metadata["package"] ?: "")
        DailyEvent.Type.TYPING -> "Typing"
        DailyEvent.Type.MEETING -> "Meeting / conversation"
        DailyEvent.Type.SLEEP -> "Sleep"
    }

    private fun iconFor(type: DailyEvent.Type): String = when (type) {
        DailyEvent.Type.WALK -> "🚶"
        DailyEvent.Type.CALL -> "📞"
        DailyEvent.Type.APP_USE -> "📱"
        DailyEvent.Type.TYPING -> "⌨️"
        DailyEvent.Type.MEETING -> "👥"
        DailyEvent.Type.SLEEP -> "🌙"
    }

    private fun moodColor(mood: String): Int = when (mood) {
        "HAPPY" -> R.color.verdict_good
        "STRESSED", "SAD" -> R.color.verdict_high
        "TIRED" -> R.color.verdict_fair
        else -> R.color.verdict_fair
    }

    /**
     * Renders the AI window verdict card from the most-recent
     * [com.riad.bio_marker.core.WindowSummaryStore] entry. Hidden when the
     * user has never run a monitoring window, when the window is still in
     * progress, or when the AI call hasn't completed yet.
     */
    private fun renderAiCard(view: View) {
        val ctx = requireContext()
        val card = view.findViewById<LinearLayout>(R.id.tlAiCard)
        val summary = ServiceLocator.windowSummaryStore.latest()
        if (summary == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val headline = view.findViewById<TextView>(R.id.tlAiHeadline)
        val analysis = view.findViewById<TextView>(R.id.tlAiAnalysis)
        val recsTitle = view.findViewById<TextView>(R.id.tlAiRecsTitle)
        val recsBox = view.findViewById<LinearLayout>(R.id.tlAiRecs)
        val meta = view.findViewById<TextView>(R.id.tlAiMeta)
        recsBox.removeAllViews()

        if (summary.aiHeadline.isNullOrBlank()) {
            // Either AI is disabled (no GROQ_API_KEY) or the call is pending.
            headline.text = ctx.getString(R.string.window_ai_card_empty)
            analysis.visibility = View.GONE
            recsTitle.visibility = View.GONE
            recsBox.visibility = View.GONE
        } else {
            headline.text = summary.aiHeadline
            analysis.visibility = View.VISIBLE
            analysis.text = summary.aiAnalysis ?: ""
            if (summary.aiRecommendations.isNotEmpty()) {
                recsTitle.visibility = View.VISIBLE
                recsBox.visibility = View.VISIBLE
                for (r in summary.aiRecommendations) recsBox.addView(recCard(ctx, r))
            } else {
                recsTitle.visibility = View.GONE
                recsBox.visibility = View.GONE
            }
        }
        meta.text = "${summary.periodHours}h window · ${summary.aiEngine ?: "no AI"}"
    }
}


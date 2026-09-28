package com.riad.bio_marker.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.riad.bio_marker.R
import com.riad.bio_marker.core.DailySummaryStore
import com.riad.bio_marker.core.MLInsightStore
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.worker.DailySummaryWorker
import com.riad.bio_marker.worker.MLInferenceWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class SummaryFragment : Fragment(R.layout.fragment_summary) {

    /** Hash of the last-rendered (date, createdAt, ml-date, scope) tuple. */
    private var lastRenderedKey: String? = null

    /** Wall-clock millis of the last "Run now" tap; used to debounce. */
    private var lastRunNowMs: Long = 0L

    /** True once we've auto-triggered today's DailySummaryWorker for this
     *  fragment instance. Prevents re-enqueueing it on every refresh tick. */
    private var autoRanTodaySummary: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.sumEditTargetsBtn).visibility = View.GONE
        view.findViewById<MaterialButton>(R.id.sumRunNowBtn)
            .setOnClickListener { runNow(it as MaterialButton) }

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
        lastRenderedKey = null
        view?.let { render(it) }
        // If today's summary hasn't been built yet (DailySummaryWorker only
        // runs nightly + chained from DailyAggregation), kick it off once so
        // the user sees today's walking / talking numbers immediately instead
        // of a stale "yesterday" card or all-zero metrics.
        maybeAutoRunTodaySummary()
    }

    /**
     * Auto-triggers [DailySummaryWorker] once per fragment lifecycle when
     * today's DailySummary is missing. This is what powers the "walking" and
     * "talking" rows on the Summary tab — without it the user would see
     * either yesterday's numbers or zeros until the nightly worker fired.
     */
    private fun maybeAutoRunTodaySummary() {
        if (autoRanTodaySummary) return
        val sumStore = ServiceLocator.dailySummaryStore
        if (sumStore.load(sumStore.todayKey()) != null) {
            // Today is already built — no need to spin up a worker.
            autoRanTodaySummary = true
            return
        }
        autoRanTodaySummary = true
        val wm = WorkManager.getInstance(requireContext())
        val summary = OneTimeWorkRequestBuilder<DailySummaryWorker>().build()
        val ml = OneTimeWorkRequestBuilder<MLInferenceWorker>().build()
        wm.beginWith(summary).then(ml).enqueue()
    }

    private fun runNow(btn: MaterialButton) {
        val now = System.currentTimeMillis()
        if (now - lastRunNowMs < DEBOUNCE_MS) return
        lastRunNowMs = now

        val wm = WorkManager.getInstance(requireContext())
        val summary = OneTimeWorkRequestBuilder<DailySummaryWorker>().build()
        val ml = OneTimeWorkRequestBuilder<MLInferenceWorker>().build()
        wm.beginWith(summary).then(ml).enqueue()

        Toast.makeText(requireContext(),
            R.string.summary_run_queued, Toast.LENGTH_SHORT).show()
        btn.isEnabled = false
        btn.alpha = 0.4f
        btn.postDelayed({
            btn.isEnabled = true
            btn.alpha = 1f
            lastRenderedKey = null
            view?.let { render(it) }
        }, DEBOUNCE_MS)
    }

    private fun render(view: View) {
        val ctx = requireContext()
        val sumStore = ServiceLocator.dailySummaryStore
        val mlStore = ServiceLocator.mlInsightStore

        val today = sumStore.load(sumStore.todayKey())
        val yesterday = sumStore.load(sumStore.yesterdayKey())
        val summary = today ?: yesterday
        val isToday = today != null

        val empty = view.findViewById<TextView>(R.id.sumEmpty)
        val container = view.findViewById<LinearLayout>(R.id.sumMetricsContainer)
        val date = view.findViewById<TextView>(R.id.sumDate)
        val headline = view.findViewById<TextView>(R.id.sumHeadline)
        val overall = view.findViewById<TextView>(R.id.sumOverall)
        val mlAnom = view.findViewById<TextView>(R.id.sumMlAnomaly)
        val mlSugg = view.findViewById<TextView>(R.id.sumMlSuggestion)
        val mlPattern = view.findViewById<TextView>(R.id.sumMlPattern)

        val insight = if (summary != null) {
            mlStore.load(summary.date) ?: mlStore.latest()
        } else mlStore.latest()

        val key = buildString {
            append(summary?.date ?: "-")
            append('|'); append(summary?.createdAt ?: 0L)
            append('|'); append(insight?.date ?: "-")
            append('|'); append(if (isToday) "T" else "Y")
        }
        if (key == lastRenderedKey) return
        lastRenderedKey = key

        container.removeAllViews()

        if (summary == null) {
            headline.setText(R.string.summary_tab_title)
            date.text = "—"
            empty.visibility = View.VISIBLE
            overall.visibility = View.GONE
            renderMl(ctx, insight, mlAnom, mlSugg, mlPattern)
            return
        }

        empty.visibility = View.GONE
        headline.setText(
            if (isToday) R.string.summary_today_headline
            else R.string.summary_yesterday_headline
        )
        date.text = "${summary.date} · ${freshness(ctx, summary.createdAt)}"

        overall.visibility = View.VISIBLE
        overall.text = when (summary.overall) {
            DailySummaryStore.OverallStatus.GOOD -> ctx.getString(R.string.summary_overall_good)
            DailySummaryStore.OverallStatus.MIXED -> ctx.getString(R.string.summary_overall_mixed)
            DailySummaryStore.OverallStatus.ATTENTION -> ctx.getString(R.string.summary_overall_attention)
        }
        overall.setBackgroundColor(ContextCompat.getColor(ctx, when (summary.overall) {
            DailySummaryStore.OverallStatus.GOOD -> R.color.verdict_good
            DailySummaryStore.OverallStatus.MIXED -> R.color.verdict_fair
            DailySummaryStore.OverallStatus.ATTENTION -> R.color.verdict_high
        }))

        addMetricCard(container,
            ctx.getString(R.string.summary_metric_sleep),
            "%.1f h".format(summary.sleepActualHrs), "7–9 h",
            progress01(summary.sleepActualHrs / 9f),
            summary.sleepZone, msgFor(summary, 0),
        )
        // Walking & talking: use the max of the stored summary number and the
        // live count computed from today's events. Without this, the cards
        // would say "0 min" all day until DailySummaryWorker re-runs at
        // night — which is the bug the user reported.
        val (liveWalkMin, liveTalkMin) = liveWalkAndTalk(ctx)
        val displayWalkMin = maxOf(summary.walkingActualMin, liveWalkMin)
        val displayTalkMin = maxOf(summary.talkingActualMin, liveTalkMin)
        addMetricCard(container,
            ctx.getString(R.string.summary_metric_walk),
            "$displayWalkMin min", "30–90 min",
            progress01(displayWalkMin / 90f),
            summary.walkZone, msgFor(summary, 1),
        )
        addMetricCard(container,
            ctx.getString(R.string.summary_metric_phone),
            "%.1f h".format(summary.phoneUsageActualHrs), "2–5 h",
            progress01(summary.phoneUsageActualHrs / 5f),
            summary.phoneZone, msgFor(summary, 2),
            invertZone = true,
        )
        addMetricCard(container,
            ctx.getString(R.string.summary_metric_talk),
            "$displayTalkMin min", "15–90 min",
            progress01(displayTalkMin / 90f),
            summary.talkZone, msgFor(summary, 3),
        )

        renderMl(ctx, insight, mlAnom, mlSugg, mlPattern)
    }

    private fun renderMl(
        ctx: android.content.Context,
        insight: MLInsightStore.Insight?,
        mlAnom: TextView, mlSugg: TextView, mlPattern: TextView,
    ) {
        if (insight != null) {
            mlAnom.text = if (insight.anomalous && insight.anomalousMetrics.isNotEmpty())
                ctx.getString(R.string.ml_anomaly_alert,
                    insight.anomalousMetrics.joinToString(", "))
            else ctx.getString(R.string.ml_anomaly_normal)
            mlAnom.setBackgroundColor(ContextCompat.getColor(ctx,
                if (insight.anomalous) R.color.trend_alert else R.color.surface_card))
            mlSugg.text = if (insight.suggestedSleepHrs != null) ctx.getString(
                R.string.ml_suggestion_body,
                "%.1f".format(insight.suggestedSleepHrs),
                insight.suggestedWalkMin ?: 0,
                "%.1f".format(insight.suggestedPhoneHrs ?: 0f),
                insight.suggestedTalkMin ?: 0,
            ) else "—"
            mlPattern.text = insight.weeklyClusterLabel
                ?: ctx.getString(R.string.ml_pattern_unknown)
        } else {
            mlAnom.text = ctx.getString(R.string.ml_anomaly_normal)
            mlAnom.setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_card))
            mlSugg.text = "—"
            mlPattern.text = ctx.getString(R.string.ml_pattern_unknown)
        }
    }

    private fun freshness(ctx: android.content.Context, createdAt: Long): String {
        if (createdAt <= 0L) return ctx.getString(R.string.summary_updated_now)
        val ageMin = ((System.currentTimeMillis() - createdAt) / 60_000L).toInt()
        return when {
            ageMin < 1 -> ctx.getString(R.string.summary_updated_now)
            ageMin < 60 -> ctx.getString(R.string.summary_updated_min, ageMin)
            else -> ctx.getString(R.string.summary_updated_hr, ageMin / 60)
        }
    }

    private fun progress01(v: Float): Int = (v.coerceIn(0f, 1f) * 100f).toInt()

    /**
     * Computes live walking & talking minutes for today directly from the
     * same sources [DailySummaryWorker] uses — so the Summary tab's
     * "walking" and "talking" cards stay accurate the whole day, not just
     * after the nightly aggregator runs.
     *
     * Returns `(walkMin, talkMin)`. Both fall back to 0 on any error.
     */
    private fun liveWalkAndTalk(ctx: android.content.Context): Pair<Int, Int> {
        return runCatching {
            val eventStore = ServiceLocator.dailyEventStore
            val events = eventStore.load(eventStore.todayKey())
            val walkFromEvents = events
                .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.WALK }
                .sumOf { ((it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L).toInt() }
            val featStore = ServiceLocator.dailyFeatureStore
            val today = featStore.load(featStore.todayKey())
            val stepsToday = today?.stepCount
                ?: ServiceLocator.metricsBus.state.value.stepsToday
            val walkFromSteps =
                (stepsToday / DailySummaryWorker.STEPS_PER_MINUTE).coerceAtLeast(0)
            val walkMin = maxOf(walkFromEvents, walkFromSteps)

            val callMinEvents = events
                .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.CALL }
                .sumOf { ((it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L).toInt() }
            val callMinLog = runCatching {
                com.riad.bio_marker.collectors.CallLogCollector(ctx)
                    .snapshotForToday()?.totalTalkMin
            }.getOrNull() ?: 0
            val messagingMin = events
                .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.APP_USE &&
                    (it.metadata["category"] == com.riad.bio_marker.core.AppCategoryMap.Category.MESSAGING.name ||
                     it.metadata["category"] == com.riad.bio_marker.core.AppCategoryMap.Category.SOCIAL.name) }
                .sumOf { ((it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L).toInt() }
            val talkMin = maxOf(callMinEvents, callMinLog) + messagingMin

            walkMin to talkMin
        }.getOrDefault(0 to 0)
    }

    private fun msgFor(s: DailySummaryStore.Summary, idx: Int): String =
        s.messages.getOrNull(idx) ?: ""

    private fun addMetricCard(
        parent: LinearLayout,
        title: String,
        actual: String,
        target: String,
        progress: Int,
        zone: DailySummaryStore.Zone,
        message: String,
        invertZone: Boolean = false,
    ) {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_card))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = title; textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val pillText = when (zone) {
            DailySummaryStore.Zone.LESS -> ctx.getString(R.string.summary_zone_less)
            DailySummaryStore.Zone.NORMAL -> ctx.getString(R.string.summary_zone_normal)
            DailySummaryStore.Zone.MORE -> ctx.getString(R.string.summary_zone_more)
        }
        val pillColor = when (zone) {
            DailySummaryStore.Zone.NORMAL -> R.color.verdict_good
            DailySummaryStore.Zone.LESS -> if (invertZone) R.color.verdict_good else R.color.verdict_fair
            DailySummaryStore.Zone.MORE -> if (invertZone) R.color.verdict_concerning else R.color.verdict_good
        }
        titleRow.addView(TextView(ctx).apply {
            text = pillText; textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.white))
            setBackgroundColor(ContextCompat.getColor(ctx, pillColor))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        card.addView(titleRow)

        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.summary_metric_value, actual, target)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.brand_primary))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
        })

        card.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = progress
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(8) }
        })

        if (message.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = message; textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            })
        }
        parent.addView(card)
    }

    companion object {
        private const val DEBOUNCE_MS = 8_000L
    }
}


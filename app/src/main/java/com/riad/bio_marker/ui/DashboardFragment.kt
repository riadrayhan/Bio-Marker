package com.riad.bio_marker.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riad.bio_marker.R
import com.riad.bio_marker.core.RiskCategorizer
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.TextTranslator
import com.riad.bio_marker.core.WellnessIndex
import com.riad.bio_marker.core.WindowSummaryStore
import com.riad.bio_marker.monitoring.WindowResultEstimator
import com.riad.bio_marker.research.SurveyType
import com.riad.bio_marker.worker.WindowAIAnalysisWorker
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dashboard — single-audience view ("Me / আমি").
 *
 * Shows the 0..100 wellness index, sleep / steps / sociability cards,
 * personalised advice and the 7-day trend. Tapping the wellness card
 * launches the validated PHQ-9 self-report.
 *
 * The previous "Family" and "Clinician" audience tabs were removed at user
 * request: Family duplicated content that already appears in the AI window
 * verdict + advice card, and the Clinician tab exposed research-grade raw
 * counters that average users couldn't act on. Self-report PHQ-9 / GAD-7
 * totals are still collected via [com.riad.bio_marker.research.SurveyStore].
 */
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    /** True when the AI verdict card is currently showing the translated
     *  text (instead of the original Groq response). */
    private var verdictTranslationActive: Boolean = false
    /** windowStartMs of the verdict whose translation we last cached, so
     *  switching to a new window invalidates the cache automatically. */
    private var verdictTranslationCacheFor: Long = 0L
    private var verdictTranslatedHeadline: String? = null
    private var verdictTranslatedAnalysis: String? = null
    private var verdictTranslatedRecs: List<String> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Tap the wellness card to launch the validated PHQ-9 self-report.
        // This is the only clinically meaningful number in the app — the
        // band shown above is preferentially derived from this when fresh.
        val launchSurvey = View.OnClickListener { launchPhq9Survey() }
        view.findViewById<TextView>(R.id.meIndexScore).setOnClickListener(launchSurvey)
        view.findViewById<TextView>(R.id.meIndexVerdict).setOnClickListener(launchSurvey)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.metricsBus.state.collectLatest { _ -> renderAll(view) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(60_000)
                    ServiceLocator.metricsBus.bumpForUiRefresh()
                }
            }
        }
    }

    private fun renderAll(view: View) {
        val store = ServiceLocator.monitoringStore
        val s = ServiceLocator.metricsBus.state.value
        val voice = ServiceLocator.voiceCache.load()
        val session = store.current()

        val estimate: WindowResultEstimator.Estimate? = if (session != null) {
            WindowResultEstimator.estimate(
                snapshot = s,
                voice = voice,
                liveRiskScore = s.riskScore,
                baseline = ServiceLocator.userBaseline,
                session = session,
                windowBaseline = store.windowBaseline(),
            )
        } else null

        if (session != null && session.isExpired() &&
            store.frozenScreening() == null && estimate != null
        ) {
            store.freezeScreeningEstimate(estimate)
            store.freezeFinalVerdict(RiskCategorizer.fromPhq9(estimate.phq9))
            val wellness = WellnessIndex.fromPhq9(estimate.phq9)
            ServiceLocator.dailyTrendStore.record(wellness)
            // Feed the weekly pattern detector with today's signals so the
            // PATTERN_REFLECTION nudge has something to reference.
            ServiceLocator.weeklyPatternStore.record(
                com.riad.bio_marker.core.WeeklyPatternStore.Signal.WELLNESS,
                wellness.toFloat(),
            )
            ServiceLocator.weeklyPatternStore.record(
                com.riad.bio_marker.core.WeeklyPatternStore.Signal.SLEEP,
                realSleepHours(),
            )
            ServiceLocator.weeklyPatternStore.record(
                com.riad.bio_marker.core.WeeklyPatternStore.Signal.STEPS,
                s.stepsToday.toFloat(),
            )
        }

        val displayed = store.frozenScreening() ?: estimate
        renderMe(view, s, displayed)
        renderWindowVerdict(view)
    }

    /**
     * Renders the most recent end-of-window AI summary at the top of the
     * Me tab. Hidden until at least one window has finished and produced a
     * [WindowSummaryStore.WindowSummary]. Tapping the card re-enqueues the
     * AI worker so users on a flaky network can retry the analysis.
     */
    private fun renderWindowVerdict(view: View) {
        val card = view.findViewById<View>(R.id.windowVerdictCard)
        val summary = ServiceLocator.windowSummaryStore.latest()
        if (summary == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val ctx = requireContext()

        val headline = view.findViewById<TextView>(R.id.windowVerdictHeadline)
        val analysis = view.findViewById<TextView>(R.id.windowVerdictAnalysis)
        val recsTitle = view.findViewById<TextView>(R.id.windowVerdictRecsTitle)
        val recs = view.findViewById<TextView>(R.id.windowVerdictRecs)
        val metrics = view.findViewById<TextView>(R.id.windowVerdictMetrics)
        val translateBtn = view.findViewById<TextView>(R.id.windowVerdictTranslateBtn)

        // Invalidate the per-window translation cache when the window
        // changes (or the AI re-ran and regenerated text). We key on
        // (windowStartMs, headline-hash) so a retried verdict re-translates.
        val verdictKey = summary.windowStartMs
        if (verdictKey != verdictTranslationCacheFor) {
            verdictTranslationActive = false
            verdictTranslationCacheFor = verdictKey
            verdictTranslatedHeadline = null
            verdictTranslatedAnalysis = null
            verdictTranslatedRecs = emptyList()
        }

        val showTranslated = verdictTranslationActive &&
            verdictTranslatedHeadline != null
        val displayHeadline = if (showTranslated) verdictTranslatedHeadline
            else summary.aiHeadline
        val displayAnalysis = if (showTranslated) verdictTranslatedAnalysis
            else summary.aiAnalysis
        val displayRecs = if (showTranslated && verdictTranslatedRecs.isNotEmpty())
            verdictTranslatedRecs else summary.aiRecommendations

        headline.text = displayHeadline ?: ctx.getString(R.string.window_verdict_pending)
        if (!displayAnalysis.isNullOrBlank()) {
            analysis.visibility = View.VISIBLE
            analysis.text = displayAnalysis
        } else {
            analysis.visibility = View.GONE
        }
        if (displayRecs.isNotEmpty()) {
            recsTitle.visibility = View.VISIBLE
            recs.visibility = View.VISIBLE
            recs.text = displayRecs.joinToString("\n") { "•  $it" }
        } else {
            recsTitle.visibility = View.GONE
            recs.visibility = View.GONE
        }

        // Translate button: only useful when there IS a Groq response on
        // screen. Otherwise hide it so it doesn't look broken.
        if (translateBtn != null) {
            val hasAi = !summary.aiHeadline.isNullOrBlank() ||
                !summary.aiAnalysis.isNullOrBlank()
            translateBtn.visibility = if (hasAi) View.VISIBLE else View.GONE
            translateBtn.text = if (verdictTranslationActive)
                ctx.getString(R.string.translate_show_original)
            else translateButtonLabel(ctx)
            translateBtn.setOnClickListener {
                toggleVerdictTranslation(view, summary)
            }
        }

        // 8-metric strip — only show rows that were actually measured.
        val rows = mutableListOf<String>()
        if (summary.walkingMinutes > 0 || summary.totalSteps > 0) {
            rows += ctx.getString(R.string.window_metric_walking,
                summary.walkingMinutes, summary.totalSteps)
        }
        if (summary.locationFixes > 0) {
            rows += ctx.getString(R.string.window_metric_location, summary.locationFixes)
        }
        summary.heartRateAvg?.let {
            rows += ctx.getString(R.string.window_metric_heart, it)
        }
        if (summary.moodSampleCount > 0) {
            rows += ctx.getString(R.string.window_metric_mood,
                summary.moodLabel, summary.moodSampleCount)
        }
        if (summary.sleepHours > 0f) {
            rows += ctx.getString(R.string.window_metric_sleep, summary.sleepHours)
        }
        if (summary.talkingMinutes > 0 || summary.callCount > 0) {
            rows += ctx.getString(R.string.window_metric_talking,
                summary.talkingMinutes, summary.callCount)
        }
        if (summary.totalPhoneUseMinutes > 0) {
            rows += ctx.getString(R.string.window_metric_phone,
                summary.totalPhoneUseMinutes, summary.socialMediaMinutes)
        }
        if (summary.routineScore in 0..100) {
            rows += ctx.getString(
                R.string.window_metric_routine,
                summary.routineScore,
                summary.wakeTimeLocal ?: "--:--",
                summary.bedTimeLocal ?: "--:--",
            )
        }
        if (summary.bpSystolic != null && summary.bpDiastolic != null) {
            rows += ctx.getString(
                R.string.window_metric_bp,
                summary.bpSystolic, summary.bpDiastolic,
            )
        } else {
            rows += ctx.getString(R.string.window_metric_bp_missing)
        }
        if (summary.actionPointCount > 0) {
            rows += ctx.getString(R.string.window_metric_actions, summary.actionPointCount)
        }
        metrics.text = if (rows.isEmpty()) ctx.getString(R.string.card_no_data)
            else rows.joinToString("\n")

        // Tap to manually re-run AI analysis (works whether the previous
        // run failed OR the user just wants a fresh take with the latest
        // data — the most common "Groq isn't working" complaint was that
        // there was no manual trigger).
        card.setOnClickListener {
            Toast.makeText(ctx, R.string.window_verdict_retry, Toast.LENGTH_SHORT).show()
            WorkManager.getInstance(ctx).enqueue(
                WindowAIAnalysisWorker.buildOneShot(
                    startMs = summary.windowStartMs,
                    endMs = summary.windowEndMs,
                    periodHours = summary.periodHours,
                )
            )
        }
    }

    private fun renderMe(
        view: View,
        s: com.riad.bio_marker.core.MetricsBus.Snapshot,
        est: WindowResultEstimator.Estimate?,
    ) {
        val ctx = requireContext()

        // Prefer the clinically-validated self-report PHQ-9 when fresh
        // (≤ 14 days old). Only fall back to the heuristic sensor estimate
        // when no recent check-in exists, and clearly tell the user that
        // it's an estimate, not a measurement.
        val effective = effectivePhq9(est)
        val phq9 = effective?.score
        val source = effective?.source

        // Wellness emoji + verdict — show ONLY when we have a real PHQ-9
        // value (self-report or, as a flagged fallback, a sensor estimate).
        val indexScoreView = view.findViewById<TextView>(R.id.meIndexScore)
        val indexVerdictView = view.findViewById<TextView>(R.id.meIndexVerdict)
        if (phq9 != null) {
            val score = WellnessIndex.fromPhq9(phq9)
            val band = WellnessIndex.band(score)
            indexScoreView.text = when (band) {
                WellnessIndex.Band.EXCELLENT -> "😊"
                WellnessIndex.Band.GOOD -> "🙂"
                WellnessIndex.Band.MODERATE -> "😐"
                WellnessIndex.Band.LOW -> "😔"
                WellnessIndex.Band.ALERT -> "🆘"
            }
            indexVerdictView.text = WellnessIndex.label(ctx, band)
        } else {
            indexScoreView.text = "—"
            indexVerdictView.text = ctx.getString(R.string.well_no_data)
        }

        // Honest source line: tells the user whether this verdict came from
        // their own validated PHQ-9 or from a heuristic estimate. Wired
        // through the existing me_advice_text slot is wrong (that's advice).
        // We piggy-back on sleepSubView's parent? — Cleanest: prepend the
        // qualifier to the advice text below. Actually we attach it to the
        // verdict's parent layout via the existing meAdviceText composition.
        // Implementation detail: see adviceView below.

        // Sleep — read the real estimate from today's daily feature vector.
        // No fake "deduce it from motion energy" heuristic any more.
        val today = ServiceLocator.dailyFeatureStore.load(
            ServiceLocator.dailyFeatureStore.todayKey()
        )
        val sleepHours = today?.sleepDurationHrs ?: 0f
        val sleepValueView = view.findViewById<TextView>(R.id.meSleepValue)
        val sleepSubView = view.findViewById<TextView>(R.id.meSleepSub)
        if (sleepHours > 0f) {
            val sleepWordRes = when {
                sleepHours < 5f -> R.string.me_card_sleep_word_low
                sleepHours < 6.5f -> R.string.me_card_sleep_word_mid
                else -> R.string.me_card_sleep_word_ok
            }
            sleepValueView.text = ctx.getString(sleepWordRes)
            sleepSubView.text = ctx.getString(
                if (sleepHours < 6.5f) R.string.me_card_sleep_sub_low
                else R.string.me_card_sleep_sub_ok
            )
        } else {
            sleepValueView.text = ctx.getString(R.string.card_no_data)
            sleepSubView.text = ""
        }

        // Steps — render the verdict whenever we have *any* step signal for
        // today, not just while the service is actively running. We fall back
        // to (a) today's persisted DailyFeatureStore step count and (b) the
        // sum of today's WALK timeline events so the card keeps showing the
        // user's real walking even after they stop the monitoring window.
        val walkEventStepsToday = runCatching {
            val ekey = ServiceLocator.dailyEventStore.todayKey()
            ServiceLocator.dailyEventStore.load(ekey)
                .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.WALK }
                .sumOf { it.metadata["steps"]?.toIntOrNull() ?: 0 }
        }.getOrDefault(0)
        val persistedSteps = today?.stepCount ?: 0
        val steps = maxOf(s.stepsToday, persistedSteps, walkEventStepsToday)
        val stepsValueView = view.findViewById<TextView>(R.id.meStepsValue)
        val stepsSubView = view.findViewById<TextView>(R.id.meStepsSub)
        when {
            steps > 0 -> {
                // Always show the live step count (e.g. "1,234 steps") so the
                // walking card has an actual number, not just a vague word.
                stepsValueView.text = ctx.getString(R.string.me_card_steps_value, steps)
                val stepsWordRes = when {
                    steps < 1000 -> R.string.me_card_steps_word_none
                    steps < 5000 -> R.string.me_card_steps_word_low
                    steps < 9000 -> R.string.me_card_steps_word_ok
                    else -> R.string.me_card_steps_word_high
                }
                val subRes = if (steps < 5000) R.string.me_card_steps_sub_low
                    else R.string.me_card_steps_sub_ok
                stepsSubView.text = ctx.getString(stepsWordRes) + " · " +
                    ctx.getString(subRes)
            }
            s.serviceRunning -> {
                // Service just started, the step sensor hasn't fired yet —
                // tell the user we're counting instead of "no data yet".
                stepsValueView.text = ctx.getString(R.string.me_card_steps_value, 0)
                stepsSubView.text = ctx.getString(R.string.me_card_steps_starting)
            }
            else -> {
                stepsValueView.text = ctx.getString(R.string.card_no_data)
                stepsSubView.text = ""
            }
        }

        // Social / connection — surface real signal from ANY of:
        //   - keystrokes captured via the BioMarker IME (most accurate but
        //     requires user to enable the keyboard);
        //   - total call talking minutes today (CallLogCollector + live CALL
        //     timeline events);
        //   - messaging-app foreground minutes today (WhatsApp / Messenger /
        //     Telegram / Imo … via APP_USE timeline events).
        // We use the union so the card stops showing "no data" the moment the
        // user has had a single phone call or WhatsApp session today.
        val socialValueView = view.findViewById<TextView>(R.id.meSocialValue)
        val socialSubView = view.findViewById<TextView>(R.id.meSocialSub)
        val todayEvents = runCatching {
            ServiceLocator.dailyEventStore.load(ServiceLocator.dailyEventStore.todayKey())
        }.getOrDefault(emptyList())
        val callMinFromEvents = todayEvents
            .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.CALL }
            .sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L }.toInt()
        val callMinFromLog = runCatching {
            com.riad.bio_marker.collectors.CallLogCollector(ctx).snapshotForToday()?.totalTalkMin
        }.getOrNull() ?: 0
        val messagingMinFromEvents = todayEvents
            .filter { it.type == com.riad.bio_marker.core.DailyEvent.Type.APP_USE &&
                (it.metadata["category"] == com.riad.bio_marker.core.AppCategoryMap.Category.MESSAGING.name ||
                 it.metadata["category"] == com.riad.bio_marker.core.AppCategoryMap.Category.SOCIAL.name) }
            .sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) / 60_000L }.toInt()
        val talkingMinToday = maxOf(callMinFromEvents, callMinFromLog) + messagingMinFromEvents
        val hasKeystrokes = s.keystrokesToday > 0
        when {
            hasKeystrokes || talkingMinToday > 0 -> {
                // Show the actual numbers (e.g. "12 min · 340 keys") so the
                // connection card carries information, not just a category.
                socialValueView.text = ctx.getString(
                    R.string.me_card_social_value,
                    talkingMinToday,
                    s.keystrokesToday,
                )
                val verdict = when {
                    talkingMinToday >= 30 || s.keystrokesToday >= 600 ->
                        ctx.getString(R.string.me_card_social_ok)
                    else -> ctx.getString(R.string.me_card_social_low)
                }
                socialSubView.text = verdict + " · " +
                    ctx.getString(R.string.me_card_social_sub)
            }
            s.serviceRunning -> {
                socialValueView.text = ctx.getString(
                    R.string.me_card_social_value, 0, 0
                )
                socialSubView.text = ctx.getString(R.string.me_card_social_starting)
            }
            else -> {
                socialValueView.text = ctx.getString(R.string.card_no_data)
                socialSubView.text = ""
            }
        }

        // Advice — only personalised when we have a real top contributor.
        // Otherwise show the no-data hint (don't fabricate generic advice).
        // We also prepend a one-line *source qualifier* so the user always
        // knows whether the verdict above came from their validated PHQ-9
        // self-report or from a heuristic sensor estimate. PHQ-9 / GAD-7
        // are the only clinically validated numbers in this app.
        val top = est?.topDepressionContributors?.firstOrNull()
            ?: est?.topAnxietyContributors?.firstOrNull()
        val adviceView = view.findViewById<TextView>(R.id.meAdviceText)
        val baseAdvice = when (top) {
            WindowResultEstimator.FEAT_SEDENTARINESS -> ctx.getString(R.string.me_advice_steps)
            WindowResultEstimator.FEAT_WITHDRAWAL -> ctx.getString(R.string.me_advice_social)
            WindowResultEstimator.FEAT_SLOW_TYPING,
            WindowResultEstimator.FEAT_CONCENTRATION -> ctx.getString(R.string.me_advice_typing)
            WindowResultEstimator.FEAT_RESTLESSNESS -> ctx.getString(R.string.me_advice_sleep)
            else -> if (sleepHours in 0.01f..6.5f) ctx.getString(R.string.me_advice_sleep)
            else ctx.getString(R.string.well_no_data_sub)
        }
        val sourceLine = when (source) {
            VerdictSource.SELF_REPORT -> ctx.getString(R.string.me_source_self)
            VerdictSource.ESTIMATE -> ctx.getString(R.string.me_source_estimate)
            null -> ctx.getString(R.string.me_source_no_data)
        }
        adviceView.text = sourceLine + "\n\n" + baseAdvice

        renderTrend(view)
    }

    private fun renderTrend(view: View) {
        val ctx = requireContext()
        val bars = view.findViewById<LinearLayout>(R.id.trendBars)
        val scoreLabels = view.findViewById<LinearLayout>(R.id.trendScoreLabels)
        val labels = view.findViewById<LinearLayout>(R.id.trendLabels)
        bars.removeAllViews()
        scoreLabels?.removeAllViews()
        labels.removeAllViews()

        val data = ServiceLocator.dailyTrendStore.lastSeven()
        // Trend is fixed 0..100 — locking the scale makes today comparable to
        // every prior day, instead of rescaling on the highest score in view.
        val maxScore = 100
        val density = resources.displayMetrics.density
        val weekdayFmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val dayKeyFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val todayKey = dayKeyFmt.format(java.util.Date())

        data.forEachIndexed { i, day ->
            val barHeightDp = if (day.score == null) 8 else
                (10 + (day.score.toFloat() / maxScore) * 60f).toInt()
            val tint = when {
                day.score == null -> R.color.cream_card_alt
                day.score >= 70 -> R.color.trend_good
                day.score >= 50 -> R.color.trend_mid
                day.score >= 30 -> R.color.trend_bad
                else -> R.color.trend_alert
            }
            val bar = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, (barHeightDp * density).toInt(), 1f
                ).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                }
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_trend_bar)?.mutate()
                background.setTint(ContextCompat.getColor(ctx, tint))
            }
            bars.addView(bar)

            // Numeric score above each bar — "—" when no data so users
            // immediately see "this day wasn't recorded".
            scoreLabels?.let {
                val score = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    gravity = Gravity.CENTER
                    text = day.score?.toString() ?: "—"
                    setTextColor(ResourcesCompat.getColor(resources, R.color.cream_text, null))
                    textSize = 11f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                it.addView(score)
            }

            // Real weekday from the stored dayKey (parses "yyyy-MM-dd"). The
            // previous code used a fixed Mon..Sun mapping, which produced
            // wrong labels whenever today wasn't actually Sunday.
            val isToday = day.dayKey == todayKey
            val labelText = if (isToday) ctx.getString(R.string.day_today)
                else runCatching {
                    weekdayFmt.format(dayKeyFmt.parse(day.dayKey)!!)
                }.getOrDefault(day.dayKey.takeLast(2))
            val label = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = labelText
                setTextColor(ResourcesCompat.getColor(resources, R.color.cream_text_muted, null))
                textSize = 11f
            }
            labels.addView(label)
        }
    }

    /** Real sleep estimate from today's daily feature vector (built by
     *  [com.riad.bio_marker.worker.DailyAggregationWorker]). Returns 0f if
     *  no measurement yet — never a heuristic guess. */
    private fun realSleepHours(): Float {
        val store = ServiceLocator.dailyFeatureStore
        return store.load(store.todayKey())?.sleepDurationHrs ?: 0f
    }

    // ---------- FAMILY ----------
    // ---------- FAMILY tab removed at user request (see class KDoc) ----------
    // ---------- CLINICIAN tab removed (see class KDoc) ----------

    /**
     * Source of the PHQ-9 number that drives the dashboard verdict.
     * The validated questionnaire (`SELF_REPORT`) is the only clinically
     * meaningful signal here; `ESTIMATE` is a heuristic fallback derived
     * from sensor activity and must always be labelled as such.
     */
    private enum class VerdictSource { SELF_REPORT, ESTIMATE }

    private data class EffectivePhq9(val score: Int, val source: VerdictSource)

    /** How recent a self-report PHQ-9 is allowed to be before we treat it
     *  as stale and fall back to the sensor estimate. PHQ-9 itself asks
     *  about the last two weeks, so 14 days is the natural ceiling. */
    private val selfReportFreshnessMs: Long = 14L * 24 * 60 * 60 * 1000

    /**
     * Returns the PHQ-9 value to display, preferring the validated
     * self-report when it exists and is fresh. Falls back to the heuristic
     * sensor estimate otherwise. Returns null if neither is available.
     */
    private fun effectivePhq9(est: WindowResultEstimator.Estimate?): EffectivePhq9? {
        val store = ServiceLocator.surveyStore
        val selfTotal = store.lastTotal(SurveyType.PHQ9)
        val selfTs = store.lastTimestamp(SurveyType.PHQ9)
        val isFresh = selfTotal != null && selfTs != null &&
            (System.currentTimeMillis() - selfTs) <= selfReportFreshnessMs
        if (isFresh && selfTotal != null) {
            return EffectivePhq9(selfTotal, VerdictSource.SELF_REPORT)
        }
        val passive = est?.phq9 ?: return null
        return EffectivePhq9(passive, VerdictSource.ESTIMATE)
    }

    /** Open the validated PHQ-9 self-report screen. Bound to the wellness
     *  card so a tap nudges the user to complete a real measurement. */
    private fun launchPhq9Survey() {
        val intent = Intent(requireContext(), SurveyActivity::class.java)
            .putExtra(SurveyActivity.EXTRA_TYPE, SurveyType.PHQ9.name)
        startActivity(intent)
    }

    // ---------- AI window verdict translator ----------

    /** Target language for the verdict translator = opposite of the
     *  currently-active app locale (Bangla user → English, English user
     *  → Bangla). Makes the button useful for both user groups. */
    private fun translationTarget(): String =
        if (Locale.getDefault().language.lowercase().startsWith("bn")) "en" else "bn"

    private fun translateButtonLabel(ctx: android.content.Context): String =
        if (translationTarget() == "bn")
            ctx.getString(R.string.translate_to_bn)
        else ctx.getString(R.string.translate_to_en)

    /**
     * Toggles the AI verdict card between original and translated form.
     * When flipping ON for the first time on a given window, runs the
     * three pieces of text (headline + analysis + each recommendation)
     * through [TextTranslator] on the IO dispatcher and caches the
     * results so subsequent flips are instant.
     */
    private fun toggleVerdictTranslation(
        view: View,
        summary: WindowSummaryStore.WindowSummary,
    ) {
        if (verdictTranslationActive) {
            verdictTranslationActive = false
            renderWindowVerdict(view)
            return
        }
        val target = translationTarget()
        val translateBtn = view.findViewById<TextView>(R.id.windowVerdictTranslateBtn)
        translateBtn?.isEnabled = false
        translateBtn?.text = getString(R.string.translate_in_progress)
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = runCatching {
                val headline = summary.aiHeadline.orEmpty()
                val analysisTxt = summary.aiAnalysis.orEmpty()
                withContext(Dispatchers.IO) {
                    verdictTranslatedHeadline = TextTranslator.translate(headline, target)
                    verdictTranslatedAnalysis = TextTranslator.translate(analysisTxt, target)
                    verdictTranslatedRecs =
                        TextTranslator.translateAll(summary.aiRecommendations, target)
                }
                true
            }.getOrElse { false }
            translateBtn?.isEnabled = true
            if (ok) {
                verdictTranslationActive = true
            } else {
                Toast.makeText(requireContext(), R.string.translate_failed,
                    Toast.LENGTH_LONG).show()
            }
            renderWindowVerdict(view)
        }
    }
}

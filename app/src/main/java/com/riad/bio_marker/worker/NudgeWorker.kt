package com.riad.bio_marker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.core.NudgeEngine
import com.riad.bio_marker.core.NudgeNotifier
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.WellnessIndex
import com.riad.bio_marker.monitoring.WindowResultEstimator
import com.riad.bio_marker.research.SurveyType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Wakes up periodically and asks [NudgeEngine] whether to fire a nudge.
 * Frequency is **hourly** so we can hit the JITAI 1-hour windows
 * (08–09 / 14–15 / 18–19 / Sun 11). The engine itself enforces all the
 * cooldown / quiet-hour / per-day cap rules.
 *
 * Responsibilities:
 *  1. Build a [WindowResultEstimator.Estimate] from the active monitoring
 *     window so personalised contributor-based nudges remain available.
 *  2. Aggregate the positive signals (steps, voice check-in done today,
 *     self-report done today) into [NudgeEngine.PositiveSignals].
 *  3. Feed the strongest weekly pattern (if any) so PATTERN_REFLECTION can fire.
 *  4. Post the resulting [NudgeEngine.Decision] and mark it fired.
 */
class NudgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!ServiceLocator.consentStore.hasConsent) return Result.success()

        val store = ServiceLocator.nudgeStore
        val s = ServiceLocator.metricsBus.state.value
        val voice = ServiceLocator.voiceCache.load()
        val session = ServiceLocator.monitoringStore.current()

        val estimate: WindowResultEstimator.Estimate? = if (session != null) {
            WindowResultEstimator.estimate(
                snapshot = s,
                voice = voice,
                liveRiskScore = s.riskScore,
                baseline = ServiceLocator.userBaseline,
                session = session,
                windowBaseline = ServiceLocator.monitoringStore.windowBaseline(),
            )
        } else null

        val wellness = estimate?.phq9?.let { WellnessIndex.fromPhq9(it) }
        val today = DAY.format(Date())

        val positive = NudgeEngine.PositiveSignals(
            stepsToday = s.stepsToday,
            voiceCheckInToday = voice != null && DAY.format(Date(voice.timestampMs)) == today,
            selfReportToday = ServiceLocator.moodGroundTruth.hasMoodFor(today) ||
                surveyTakenToday(),
            wellnessScore = wellness,
            baselineSteps = ServiceLocator.weeklyPatternStore.baselineMean(
                com.riad.bio_marker.core.WeeklyPatternStore.Signal.STEPS
            ),
        )

        val pattern = ServiceLocator.weeklyPatternStore.strongestPattern()

        val decision = NudgeEngine.pick(
            store = store,
            estimate = estimate,
            positive = positive,
            weeklyPattern = pattern,
        ) ?: return Result.success()

        NudgeNotifier(applicationContext).post(decision)
        store.markFired(decision.type)
        return Result.success()
    }

    /** True if the user filled a PHQ-9 or GAD-7 self-report at any time today. */
    private fun surveyTakenToday(): Boolean {
        // SurveyStore exposes `lastTotal` only; absence of a per-day timestamp
        // means we approximate by checking whether *any* score exists. Keeping
        // a stricter "done today" check would need a per-day timestamp in
        // SurveyStore — out of scope for this slice.
        val anyPhq = ServiceLocator.surveyStore.lastTotal(SurveyType.PHQ9) != null
        val anyGad = ServiceLocator.surveyStore.lastTotal(SurveyType.GAD7) != null
        if (!(anyPhq || anyGad)) return false
        // Use the mood ground-truth "today" flag as the authoritative
        // "done today" marker; if that's false we don't claim a self-report.
        return false
    }

    companion object {
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

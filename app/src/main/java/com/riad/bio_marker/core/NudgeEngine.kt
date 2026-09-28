package com.riad.bio_marker.core

import com.riad.bio_marker.monitoring.WindowResultEstimator
import java.util.Calendar

/**
 * JITAI (Just-In-Time Adaptive Intervention) nudge router.
 *
 * Time-of-day windows (cortisol-aware):
 *  - 08:00–09:59  → CHECK_IN (morning, user is most receptive)
 *  - 14:00–15:59  → MICRO_ACTION (post-lunch dip — tiny actions land best)
 *  - 18:00–19:59  → VALIDATION (end-of-day positive reinforcement)
 *  - Sunday 11:00 → PATTERN_REFLECTION (weekly slot, only if a pattern exists)
 *  - 22:00–07:59  → no non-crisis nudges (sleep biomarker protection)
 *
 * Crisis path bypasses everything except its own 6h cooldown, and a "low"
 * mood emoji tap can flag a CRISIS_SUPPORT follow-up immediately via
 * [NudgeStore.requestCrisisFollowUp].
 *
 * Personalised contributor-driven nudges (ACTIVITY/SLEEP/BREATHING/SOCIAL)
 * remain available outside the JITAI slots when the signal is strong, but
 * are de-prioritised so they don't crowd the time-window nudges.
 */
object NudgeEngine {

    data class PositiveSignals(
        val stepsToday: Int,
        val voiceCheckInToday: Boolean,
        val selfReportToday: Boolean,
        val wellnessScore: Int?,
        /** User's personal baseline mean for daily steps (from [WeeklyPatternStore]).
         *  Null when we don't yet have enough samples to know what's "normal" for them. */
        val baselineSteps: Float? = null,
    )

    data class Decision(
        val type: NudgeStore.Type,
        val pattern: WeeklyPatternStore.Pattern? = null,
    )

    private const val MAX_PER_DAY = 2
    private const val MIN_GAP_HOURS = 4f
    private const val TYPE_COOLDOWN_HOURS = 20f
    private const val CRISIS_COOLDOWN_HOURS = 6f
    private const val CHECK_IN_COOLDOWN_HOURS = 22f       // ~once/day
    private const val VALIDATION_COOLDOWN_HOURS = 22f
    private const val MICRO_ACTION_COOLDOWN_HOURS = 22f
    private const val PATTERN_COOLDOWN_HOURS = 24f * 6    // weekly
    private const val ENCOURAGEMENT_COOLDOWN_HOURS = 24f * 2

    fun pick(
        store: NudgeStore,
        estimate: WindowResultEstimator.Estimate?,
        positive: PositiveSignals,
        weeklyPattern: WeeklyPatternStore.Pattern?,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision? {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK)

        // ----- Crisis fast-path. -----
        val band = positive.wellnessScore?.let { WellnessIndex.band(it) }
        val crisisRequested = store.consumeCrisisFollowUp()
        if (band == WellnessIndex.Band.ALERT || crisisRequested) {
            if (crisisRequested ||
                store.hoursSince(NudgeStore.Type.CRISIS_SUPPORT, nowMs) >= CRISIS_COOLDOWN_HOURS
            ) {
                return Decision(NudgeStore.Type.CRISIS_SUPPORT)
            }
            return null
        }

        // ----- Hard sleep guard: 22:00–07:59 stays silent for everything but crisis. -----
        if (hour >= 22 || hour < 8) return null
        if (store.quietMode) return null
        if (store.firedTodayCount() >= MAX_PER_DAY) return null
        if (anyNudgeWithinGap(store, nowMs)) return null

        // ===== JITAI time-window routing =====

        // 08:00–09:59 — Check-in (skip if user already tapped a mood today).
        if (hour in 8..9 &&
            !positive.selfReportToday &&
            !store.firedTodayForType(NudgeStore.Type.CHECK_IN) &&
            store.hoursSince(NudgeStore.Type.CHECK_IN, nowMs) >= CHECK_IN_COOLDOWN_HOURS
        ) {
            return Decision(NudgeStore.Type.CHECK_IN)
        }

        // Sunday 11:00–11:59 — Pattern reflection (weekly slot).
        if (dow == Calendar.SUNDAY && hour == 11 &&
            weeklyPattern != null &&
            !store.firedTodayForType(NudgeStore.Type.PATTERN_REFLECTION) &&
            store.hoursSince(NudgeStore.Type.PATTERN_REFLECTION, nowMs) >= PATTERN_COOLDOWN_HOURS
        ) {
            return Decision(NudgeStore.Type.PATTERN_REFLECTION, pattern = weeklyPattern)
        }

        // 14:00–15:59 — Micro-action when activity is low.
        if (hour in 14..15 &&
            isLowActivity(estimate, positive) &&
            !store.firedTodayForType(NudgeStore.Type.MICRO_ACTION) &&
            store.hoursSince(NudgeStore.Type.MICRO_ACTION, nowMs) >= MICRO_ACTION_COOLDOWN_HOURS
        ) {
            return Decision(NudgeStore.Type.MICRO_ACTION)
        }

        // 18:00–19:59 — Validation when there's *any* positive signal today.
        if (hour in 18..19 &&
            hasPositiveSignal(positive) &&
            !store.firedTodayForType(NudgeStore.Type.VALIDATION) &&
            store.hoursSince(NudgeStore.Type.VALIDATION, nowMs) >= VALIDATION_COOLDOWN_HOURS
        ) {
            return Decision(NudgeStore.Type.VALIDATION)
        }

        // ===== Outside the JITAI slots: contributor-driven personalised nudges. =====
        val top = estimate?.topDepressionContributors?.firstOrNull()
            ?: estimate?.topAnxietyContributors?.firstOrNull()
        val candidate = when (top) {
            WindowResultEstimator.FEAT_SEDENTARINESS -> NudgeStore.Type.ACTIVITY
            WindowResultEstimator.FEAT_RESTLESSNESS -> NudgeStore.Type.SLEEP
            WindowResultEstimator.FEAT_CONCENTRATION,
            WindowResultEstimator.FEAT_SLOW_TYPING -> NudgeStore.Type.BREATHING
            WindowResultEstimator.FEAT_WITHDRAWAL,
            WindowResultEstimator.FEAT_VOICE_FLAT -> NudgeStore.Type.SOCIAL
            else -> null
        }
        if (candidate != null &&
            store.hoursSince(candidate, nowMs) >= TYPE_COOLDOWN_HOURS
        ) {
            return Decision(candidate)
        }

        // Encouragement when wellness has been good for a while (fallback).
        if ((band == WellnessIndex.Band.EXCELLENT || band == WellnessIndex.Band.GOOD) &&
            store.hoursSince(NudgeStore.Type.ENCOURAGEMENT, nowMs) >= ENCOURAGEMENT_COOLDOWN_HOURS
        ) {
            return Decision(NudgeStore.Type.ENCOURAGEMENT)
        }

        return null
    }

    private fun isLowActivity(
        estimate: WindowResultEstimator.Estimate?,
        positive: PositiveSignals,
    ): Boolean {
        // Step-count heuristic OR sedentariness in top contributors.
        if (positive.stepsToday in 0..2000) return true
        val tops = (estimate?.topDepressionContributors.orEmpty() +
            estimate?.topAnxietyContributors.orEmpty())
        return WindowResultEstimator.FEAT_SEDENTARINESS in tops
    }

    private fun hasPositiveSignal(p: PositiveSignals): Boolean {
        // Anything we can authentically thank them for — depression makes people
        // discount their own wins, so the threshold is intentionally generous.
        // Prefer a personal-baseline comparison when we have one (≥ 1.2× baseline
        // = "got out today, more than usual"). Fall back to an absolute floor.
        val base = p.baselineSteps
        if (base != null && base > 0f && p.stepsToday >= base * 1.2f) return true
        if (p.stepsToday >= 3000) return true
        if (p.voiceCheckInToday) return true
        if (p.selfReportToday) return true
        return false
    }

    private fun anyNudgeWithinGap(store: NudgeStore, nowMs: Long): Boolean {
        return NudgeStore.Type.values()
            .filter { it != NudgeStore.Type.CRISIS_SUPPORT }
            .any { store.hoursSince(it, nowMs) < MIN_GAP_HOURS }
    }
}


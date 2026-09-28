package com.riad.bio_marker.monitoring

import com.riad.bio_marker.core.DailyFeatureStore

/**
 * Implements the five spec triggers — any one fires a crisis event:
 *
 *  1. Risk score > 0.85 for 2 consecutive days
 *  2. Risk score dropped ≥ 34 % vs 72 h ago
 *  3. Phone use after 02:00 on 4+ consecutive days
 *  4. Sleep < 3 h on 3 consecutive nights
 *  5. Voice energy ≥ 95 % below the user's baseline
 */
object CrisisRules {

    enum class Trigger {
        SUSTAINED_HIGH_RISK,
        RAPID_DROP,
        DEEP_NIGHT_USAGE,
        SEVERE_SLEEP_LOSS,
        VOICE_COLLAPSE,
    }

    data class Snapshot(
        /** Most recent → oldest, normalised to 0..1. */
        val recentRiskScores: List<Float>,
        /** Most recent → oldest. */
        val recentSleepHrs: List<Float>,
        /** Minutes of phone use between 02:00–05:00 per day, most recent first. */
        val recentDeepNightMin: List<Int>,
        /** Most recent voice mean energy (RMS, 0..1) or null. */
        val voiceEnergyNow: Float?,
        /** Long-term baseline of voice energy or null. */
        val voiceEnergyBaseline: Float?,
    )

    fun fromDailies(dailies: List<DailyFeatureStore.Daily>, voiceBaseline: Float?): Snapshot {
        val rev = dailies.reversed() // newest first
        return Snapshot(
            recentRiskScores = emptyList(),  // risk scored separately, callers fill
            recentSleepHrs = rev.map { it.sleepDurationHrs },
            recentDeepNightMin = rev.map { it.lateNightUsageMin },
            voiceEnergyNow = rev.firstOrNull()?.voiceEnergyMean,
            voiceEnergyBaseline = voiceBaseline,
        )
    }

    fun evaluate(s: Snapshot): List<Trigger> {
        val out = mutableListOf<Trigger>()

        // 1) Sustained high risk (need today + yesterday)
        if (s.recentRiskScores.size >= 2 &&
            s.recentRiskScores[0] > 0.85f && s.recentRiskScores[1] > 0.85f
        ) out += Trigger.SUSTAINED_HIGH_RISK

        // 2) Rapid drop: today vs 3 days ago, ≥ 34% relative drop in wellness
        // (= score increase, since "score" here means risk).
        if (s.recentRiskScores.size >= 4) {
            val today = s.recentRiskScores[0]
            val past = s.recentRiskScores[3]
            if (past > 0.05f && (today - past) / past >= 0.34f) out += Trigger.RAPID_DROP
        }

        // 3) 4+ consecutive days with significant post-02:00 use (≥10 min).
        val nights = s.recentDeepNightMin.takeWhile { it >= 10 }.size
        if (nights >= 4) out += Trigger.DEEP_NIGHT_USAGE

        // 4) 3 consecutive nights of <3 h sleep (ignoring zero/missing days).
        val streak = s.recentSleepHrs
            .filter { it > 0f }       // skip days with no estimate
            .takeWhile { it < 3f }
            .size
        if (streak >= 3) out += Trigger.SEVERE_SLEEP_LOSS

        // 5) Voice energy ≥ 95 % below baseline.
        val now = s.voiceEnergyNow
        val base = s.voiceEnergyBaseline
        if (now != null && base != null && base > 0f && now <= base * 0.05f) {
            out += Trigger.VOICE_COLLAPSE
        }
        return out
    }
}


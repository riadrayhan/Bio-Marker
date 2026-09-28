package com.riad.bio_marker.rules

import com.riad.bio_marker.core.AppCategoryMap
import com.riad.bio_marker.core.DailyEvent
import com.riad.bio_marker.core.DailyReportStore
import com.riad.bio_marker.core.DailySummaryStore

/**
 * Builds an end-of-day [DailyReportStore.Report] from:
 *   - the chronological list of [DailyEvent]s for the day,
 *   - the rule-based [DailySummaryStore.Summary] (4-metric verdict already
 *     produced by [RuleEngine]).
 *
 * Output:
 *   - **headline**: short overall verdict (re-uses summary headline + a
 *     mood-aware suffix when most events skewed negative).
 *   - **perEventNotes**: one short health note per event (via [EventNarrator]).
 *   - **recommendations**: a deduplicated, ordered list of actionable items
 *     ("Sleep 9–10 h tonight", "Cap social media to under 1 h", "Listen to
 *     calming music for 15 min", …) selected from a small rule set.
 *
 * This is the v1 narrative engine — pure rules. A learned ranker can later
 * replace [pickRecommendations] without changing the report shape.
 */
object RecommendationEngine {

    fun build(
        date: String,
        events: List<DailyEvent>,
        summary: DailySummaryStore.Summary?,
    ): DailyReportStore.Report {

        val notes = events.map { EventNarrator.narrate(it) }

        // ---- Aggregated signals used by the recommendation rules ----
        val socialMin = events
            .filter { it.type == DailyEvent.Type.APP_USE
                && it.metadata["category"] == AppCategoryMap.Category.SOCIAL.name }
            .sumOf { it.durationMin }
        val videoMin = events
            .filter { it.type == DailyEvent.Type.APP_USE
                && it.metadata["category"] == AppCategoryMap.Category.VIDEO.name }
            .sumOf { it.durationMin }
        val callMin = events.filter { it.type == DailyEvent.Type.CALL }.sumOf { it.durationMin }
        val walkMin = events.filter { it.type == DailyEvent.Type.WALK }.sumOf { it.durationMin }
        val negativeMoodCount = events.count {
            it.moodLabel in setOf("STRESSED", "SAD", "TIRED")
        }
        val sleepHrs = summary?.sleepActualHrs ?: 0f
        val phoneHrs = summary?.phoneUsageActualHrs ?: 0f

        // ---- Recommendations (rule-based) ----
        val recs = pickRecommendations(
            sleepHrs = sleepHrs,
            phoneHrs = phoneHrs,
            socialMin = socialMin,
            videoMin = videoMin,
            callMin = callMin,
            walkMin = walkMin,
            negativeMoodCount = negativeMoodCount,
        )

        // ---- Headline ----
        val baseHeadline = summary?.let { headlineFor(it.overall) }
            ?: "Today's recap"
        val moodSuffix = when {
            negativeMoodCount >= 3 -> " — you seemed under stress today."
            negativeMoodCount == 0 && events.isNotEmpty() -> " — overall mood looked positive."
            else -> ""
        }
        val headline = baseHeadline + moodSuffix

        return DailyReportStore.Report(
            date = date,
            headline = headline,
            perEventNotes = notes,
            recommendations = recs,
            createdAt = System.currentTimeMillis(),
        )
    }

    private fun headlineFor(s: DailySummaryStore.OverallStatus): String = when (s) {
        DailySummaryStore.OverallStatus.GOOD -> "Solid day — you hit your targets."
        DailySummaryStore.OverallStatus.MIXED -> "Mixed day — some areas to improve."
        DailySummaryStore.OverallStatus.ATTENTION -> "Today needs some care."
    }

    private fun pickRecommendations(
        sleepHrs: Float,
        phoneHrs: Float,
        socialMin: Int,
        videoMin: Int,
        callMin: Int,
        walkMin: Int,
        negativeMoodCount: Int,
    ): List<String> {
        val out = mutableListOf<String>()

        // Sleep guidance — the single most-impactful lever.
        if (sleepHrs > 0f && sleepHrs < 6f) {
            out += "You're under-slept (${"%.1f".format(sleepHrs)} h). Aim for 9–10 h tonight to fully recover."
        } else if (sleepHrs in 6f..6.9f) {
            out += "Try to sleep at least 7–8 h tonight."
        }

        // Eye-strain / screen-time
        if (videoMin >= 120) {
            out += "You watched video for ${videoMin} min — give your eyes a real break (look at something 20 ft away for 20 s every 20 min)."
        }
        if (socialMin >= 90) {
            out += "Social-media use was high (${socialMin} min). Cap it to under an hour tomorrow — long scrolling is linked to mood dips."
        }
        if (phoneHrs >= 5f) {
            out += "Total phone use was ${"%.1f".format(phoneHrs)} h. Set a one-hour phone-free window before bed."
        }

        // Talking / call fatigue
        if (callMin >= 90) {
            out += "Long calls today (${callMin} min total). Keep tomorrow's calls shorter or batch them with breaks."
        }

        // Movement
        if (walkMin < 20) {
            out += "Light on movement today. Try a 20-min walk tomorrow morning — even gentle pace helps."
        }

        // Mood / stress
        if (negativeMoodCount >= 3) {
            out += "You seemed tense more than once today. Listen to relaxing music for 10–15 min and try a short breathing session."
        } else if (negativeMoodCount >= 1) {
            out += "A short relaxation break (music, breathing, or a quiet walk) could help reset your mood."
        }

        // Universal closing nudge if everything else was fine
        if (out.isEmpty()) {
            out += "Looks balanced — keep your evening light and aim for 7–8 h of sleep."
        }

        // Always end with a sleep target if not already mentioned
        if (out.none { it.contains("sleep", ignoreCase = true) }) {
            out += "Aim for 7–9 h of sleep tonight."
        }

        return out
    }
}


package com.riad.bio_marker.ml.insights

import android.content.Context
import com.riad.bio_marker.core.DailySummaryStore
import com.riad.bio_marker.core.MLInsightStore

/**
 * Orchestrates the three on-device ML insight stages:
 *  1. Trend anomaly detection (last 30 days).
 *  2. Personalised target suggestions (linear-regression projection).
 *  3. Weekly K-Means cluster + worst weekday.
 *
 * Output is a single [MLInsightStore.Insight] persisted by
 * [com.riad.bio_marker.worker.MLInferenceWorker].
 */
class MLInsightsRunner(private val ctx: Context) {

    fun run(history: List<DailySummaryStore.Summary>): MLInsightStore.Insight {
        val today = if (history.isNotEmpty()) history.last().date else
            MLInsightStore(ctx).todayKey()

        val anomaly = AnomalyDetector().analyze(history)
        val suggested = TargetSuggester().suggest(history)
        val weekly = WeeklyClusterer().analyze(history)

        return MLInsightStore.Insight(
            date = today,
            anomalyScore = anomaly.anomalyScore,
            anomalous = anomaly.anomalous,
            anomalousMetrics = anomaly.anomalousMetrics,
            suggestedSleepHrs = suggested?.sleepHrs,
            suggestedWalkMin = suggested?.walkMin,
            suggestedPhoneHrs = suggested?.phoneHrs,
            suggestedTalkMin = suggested?.talkMin,
            weeklyClusterId = weekly?.clusterId,
            weeklyClusterLabel = weekly?.let {
                if (it.negativeWeekday != null) "${it.label} · weak day: ${it.negativeWeekday}"
                else it.label
            },
            createdAt = System.currentTimeMillis(),
        )
    }
}


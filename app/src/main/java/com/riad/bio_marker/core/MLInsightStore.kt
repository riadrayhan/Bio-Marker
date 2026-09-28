package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device ML-derived insights produced nightly by
 * [com.riad.bio_marker.worker.MLInferenceWorker].
 *
 *  - anomalyScore: overall reconstruction error / z-score magnitude (0..∞)
 *  - anomalousMetrics: list of metric keys that triggered the anomaly
 *  - suggested* targets: personalised recommendations from regression
 *  - weeklyClusterId/Label: K-Means cluster assignment for the past week
 */
class MLInsightStore(context: Context) {

    private val dir = java.io.File(context.filesDir, "ml_insights").apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    data class Insight(
        val date: String,
        val anomalyScore: Float = 0f,
        val anomalous: Boolean = false,
        val anomalousMetrics: List<String> = emptyList(),
        val suggestedSleepHrs: Float? = null,
        val suggestedWalkMin: Int? = null,
        val suggestedPhoneHrs: Float? = null,
        val suggestedTalkMin: Int? = null,
        val weeklyClusterId: Int? = null,
        val weeklyClusterLabel: String? = null,
        val modelVersion: String = "v1-kotlin",
        val createdAt: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("date", date)
            put("anomaly_score", anomalyScore.toDouble())
            put("anomalous", anomalous)
            put("anomalous_metrics", JSONArray(anomalousMetrics))
            suggestedSleepHrs?.let { put("suggested_sleep_hrs", it.toDouble()) }
            suggestedWalkMin?.let { put("suggested_walk_min", it) }
            suggestedPhoneHrs?.let { put("suggested_phone_hrs", it.toDouble()) }
            suggestedTalkMin?.let { put("suggested_talk_min", it) }
            weeklyClusterId?.let { put("weekly_cluster_id", it) }
            weeklyClusterLabel?.let { put("weekly_cluster_label", it) }
            put("model_version", modelVersion)
            put("created_at", createdAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Insight {
                val arr = o.optJSONArray("anomalous_metrics")
                val metrics = mutableListOf<String>()
                if (arr != null) for (i in 0 until arr.length()) metrics.add(arr.optString(i))
                return Insight(
                    date = o.optString("date"),
                    anomalyScore = o.optDouble("anomaly_score").toFloat(),
                    anomalous = o.optBoolean("anomalous", false),
                    anomalousMetrics = metrics,
                    suggestedSleepHrs = if (o.has("suggested_sleep_hrs")) o.optDouble("suggested_sleep_hrs").toFloat() else null,
                    suggestedWalkMin = if (o.has("suggested_walk_min")) o.optInt("suggested_walk_min") else null,
                    suggestedPhoneHrs = if (o.has("suggested_phone_hrs")) o.optDouble("suggested_phone_hrs").toFloat() else null,
                    suggestedTalkMin = if (o.has("suggested_talk_min")) o.optInt("suggested_talk_min") else null,
                    weeklyClusterId = if (o.has("weekly_cluster_id")) o.optInt("weekly_cluster_id") else null,
                    weeklyClusterLabel = if (o.has("weekly_cluster_label")) o.optString("weekly_cluster_label") else null,
                    modelVersion = o.optString("model_version", "v1-kotlin"),
                    createdAt = o.optLong("created_at"),
                )
            }
        }
    }

    fun save(i: Insight) {
        java.io.File(dir, "insight-${i.date}.json").writeText(i.toJson().toString())
    }

    fun load(date: String): Insight? {
        val f = java.io.File(dir, "insight-$date.json")
        if (!f.exists()) return null
        return try { Insight.fromJson(JSONObject(f.readText())) } catch (_: Exception) { null }
    }

    fun latest(): Insight? {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return null
        for (f in files) {
            try { return Insight.fromJson(JSONObject(f.readText())) } catch (_: Exception) {}
        }
        return null
    }

    fun todayKey(): String = fmt.format(Date())

    fun pruneOld(keepDays: Int = 60) {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}


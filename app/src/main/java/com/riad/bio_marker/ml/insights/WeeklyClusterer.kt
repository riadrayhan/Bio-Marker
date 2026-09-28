package com.riad.bio_marker.ml.insights

import com.riad.bio_marker.core.DailySummaryStore.Summary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.sqrt

/**
 * Weekly pattern recognition via on-device K-Means.
 *
 * We build a 7×4 matrix (one row per weekday, columns = mean sleep, walk,
 * phone, talk). Each row is then assigned to one of K clusters. The resulting
 * cluster ids are surfaced as a single human-readable label (e.g. "Mondays
 * tend to be high-phone, low-sleep") shown in the Summary screen.
 *
 * v1 ships a tiny in-Kotlin K-Means with K=3 (low-energy / balanced /
 * high-screen). The Python script `model_training/train_weekly_kmeans.py`
 * trains the same shape on synthetic data and exports centroids as
 * `assets/weekly_kmeans.json`; if that asset is shipped a future revision
 * can call [withFixedCentroids] to use them instead of clustering on-device.
 */
class WeeklyClusterer(private val k: Int = 3, private val maxIter: Int = 25) {

    data class Output(
        val clusterId: Int,
        val label: String,
        val negativeWeekday: String?, // e.g. "Monday" — the worst weekday this week
    )

    fun analyze(history: List<Summary>): Output? {
        if (history.size < 7) return null
        // Build per-weekday averages (DOW 1..7 = Sunday..Saturday in Calendar).
        val perDow = Array(7) { mutableListOf<FloatArray>() }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (s in history) {
            val d = try { fmt.parse(s.date) } catch (_: Exception) { null } ?: continue
            val cal = Calendar.getInstance().apply { time = d }
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0..6
            perDow[dow].add(
                floatArrayOf(
                    s.sleepActualHrs,
                    s.walkingActualMin.toFloat(),
                    s.phoneUsageActualHrs,
                    s.talkingActualMin.toFloat(),
                )
            )
        }
        val rows = mutableListOf<Pair<Int, FloatArray>>() // dow -> mean
        for (i in 0..6) {
            val rs = perDow[i]
            if (rs.isEmpty()) continue
            val mean = FloatArray(4)
            for (r in rs) for (j in 0..3) mean[j] += r[j]
            for (j in 0..3) mean[j] /= rs.size
            rows += i to mean
        }
        if (rows.size < 3) return null

        // z-normalise columns so kmeans isn't dominated by the walking-minutes scale.
        val zRows = normalize(rows.map { it.second })
        val centroids = kmeans(zRows, k = k.coerceAtMost(zRows.size), iters = maxIter)

        // Today's row → cluster.
        val todayDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        val todayRow = rows.firstOrNull { it.first == todayDow }?.second
            ?: rows.last().second
        val todayZ = znormalizeWithRef(todayRow, rows.map { it.second })
        val todayCluster = nearest(todayZ, centroids)

        // Worst weekday: pick the row with lowest sleep+walk+talk + highest phone.
        var worstDow = -1; var worstScore = Float.NEGATIVE_INFINITY
        for ((dow, m) in rows) {
            // Higher = worse.
            val s = -m[0] * 1f - m[1] * 0.05f + m[2] * 1f - m[3] * 0.05f
            if (s > worstScore) { worstScore = s; worstDow = dow }
        }

        return Output(
            clusterId = todayCluster,
            label = labelFor(centroids[todayCluster]),
            negativeWeekday = if (worstDow >= 0) DOW_NAMES[worstDow] else null,
        )
    }

    private fun labelFor(centroidZ: FloatArray): String {
        // Centroids are in z-space. Interpret signs:
        //   sleep+ walk+ phone- talk+   → Balanced
        //   sleep- phone+                → High-screen / low-rest
        //   walk-  talk-                 → Low-energy / quiet
        val (sleep, walk, phone, talk) = centroidZ
        return when {
            phone > 0.3f && sleep < -0.2f -> "High-screen, low-rest"
            walk < -0.3f && talk < -0.3f -> "Low-energy, quiet"
            sleep >= 0f && phone <= 0f -> "Balanced"
            else -> "Mixed"
        }
    }

    // --- KMeans helpers ---------------------------------------------------

    private fun kmeans(rows: List<FloatArray>, k: Int, iters: Int): List<FloatArray> {
        val centroids = MutableList(k) { rows[it % rows.size].copyOf() }
        val assign = IntArray(rows.size)
        for (it in 0 until iters) {
            // Assign.
            for (i in rows.indices) assign[i] = nearest(rows[i], centroids)
            // Update.
            val sums = Array(k) { FloatArray(4) }
            val counts = IntArray(k)
            for (i in rows.indices) {
                val c = assign[i]; counts[c]++
                for (j in 0..3) sums[c][j] += rows[i][j]
            }
            for (c in 0 until k) {
                if (counts[c] == 0) continue
                for (j in 0..3) centroids[c][j] = sums[c][j] / counts[c]
            }
        }
        return centroids
    }

    private fun nearest(p: FloatArray, centroids: List<FloatArray>): Int {
        var best = 0; var bestD = Float.MAX_VALUE
        for (i in centroids.indices) {
            var d = 0f
            for (j in p.indices) {
                val diff = p[j] - centroids[i][j]; d += diff * diff
            }
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun normalize(rows: List<FloatArray>): List<FloatArray> {
        val cols = 4
        val mean = FloatArray(cols); val sd = FloatArray(cols)
        for (r in rows) for (j in 0 until cols) mean[j] += r[j]
        for (j in 0 until cols) mean[j] /= rows.size
        for (r in rows) for (j in 0 until cols) sd[j] += (r[j] - mean[j]) * (r[j] - mean[j])
        for (j in 0 until cols) sd[j] = sqrt(sd[j] / rows.size).coerceAtLeast(1e-3f)
        return rows.map { r -> FloatArray(cols) { j -> (r[j] - mean[j]) / sd[j] } }
    }

    private fun znormalizeWithRef(row: FloatArray, ref: List<FloatArray>): FloatArray {
        val cols = 4
        val mean = FloatArray(cols); val sd = FloatArray(cols)
        for (r in ref) for (j in 0 until cols) mean[j] += r[j]
        for (j in 0 until cols) mean[j] /= ref.size
        for (r in ref) for (j in 0 until cols) sd[j] += (r[j] - mean[j]) * (r[j] - mean[j])
        for (j in 0 until cols) sd[j] = sqrt(sd[j] / ref.size).coerceAtLeast(1e-3f)
        return FloatArray(cols) { j -> (row[j] - mean[j]) / sd[j] }
    }

    companion object {
        private val DOW_NAMES = arrayOf(
            "Sunday", "Monday", "Tuesday", "Wednesday",
            "Thursday", "Friday", "Saturday",
        )
    }
}


package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stores the user's emoji-tapped mood ratings (😊 / 😐 / 😔) as ground-truth
 * labels. These rows get appended to a CSV-friendly JSONL in <filesDir>/mood/
 * and are intended to be paired with the same-day passive features for
 * supervised model fine-tuning later.
 *
 * One row per tap. Never overwrites — full history is kept.
 */
class MoodGroundTruth(context: Context) {

    enum class Mood(val label: String, val score: Int) {
        GOOD("good", 2),
        NEUTRAL("neutral", 1),
        LOW("low", 0),
    }

    private val dir = File(context.applicationContext.filesDir, "mood").apply { mkdirs() }
    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_mood", Context.MODE_PRIVATE)

    fun record(mood: Mood, nowMs: Long = System.currentTimeMillis()) {
        val row = JSONObject().apply {
            put("ts", nowMs)
            put("date", DAY.format(Date(nowMs)))
            put("mood", mood.label)
            put("score", mood.score)
        }
        FileWriter(currentFile(), /* append = */ true).use { w ->
            w.appendLine(row.toString())
        }
        prefs.edit()
            .putLong(K_LAST_TS, nowMs)
            .putInt(K_LAST_SCORE, mood.score)
            .putString(K_LAST_DAY, DAY.format(Date(nowMs)))
            .apply()
    }

    fun lastMoodScore(): Int? =
        if (prefs.contains(K_LAST_SCORE)) prefs.getInt(K_LAST_SCORE, 1) else null

    fun hasMoodFor(dayKey: String): Boolean =
        prefs.getString(K_LAST_DAY, "") == dayKey

    private fun currentFile(): File = File(dir, "mood-${DAY.format(Date())}.jsonl")

    companion object {
        private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private const val K_LAST_TS = "last_ts"
        private const val K_LAST_SCORE = "last_score"
        private const val K_LAST_DAY = "last_day"
    }
}


package com.riad.bio_marker.research

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists every completed PHQ-9 / GAD-7 response as one JSON line in
 * <filesDir>/surveys.jsonl, plus the most-recent total per type for quick UI access.
 * The CSV exporter pulls from this file.
 */
class SurveyStore(private val context: Context) {

    private val file: File = File(context.applicationContext.filesDir, "surveys.jsonl")
    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_survey_summary", Context.MODE_PRIVATE)

    data class Record(
        val type: SurveyType,
        val timestampMs: Long,
        val answers: List<Int>,
        val total: Int,
    )

    fun record(type: SurveyType, answers: List<Int>): Record {
        val total = answers.sum()
        val ts = System.currentTimeMillis()
        val obj = JSONObject().apply {
            put("type", type.name)
            put("ts", ts)
            put("total", total)
            put("answers", JSONArray(answers))
        }
        synchronized(this) {
            file.appendText(obj.toString() + "\n")
        }
        prefs.edit()
            .putInt("${type.name}.last_total", total)
            .putLong("${type.name}.last_ts", ts)
            .apply()
        return Record(type, ts, answers, total)
    }

    fun lastTotal(type: SurveyType): Int? {
        if (!prefs.contains("${type.name}.last_total")) return null
        return prefs.getInt("${type.name}.last_total", 0)
    }

    fun lastTimestamp(type: SurveyType): Long? {
        val ts = prefs.getLong("${type.name}.last_ts", 0L)
        return if (ts == 0L) null else ts
    }

    fun allRecords(): List<Record> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                val type = SurveyType.valueOf(o.getString("type"))
                val arr = o.getJSONArray("answers")
                val ans = (0 until arr.length()).map { arr.getInt(it) }
                Record(type, o.getLong("ts"), ans, o.getInt("total"))
            }.getOrNull()
        }
    }

    fun clear() {
        file.delete()
        prefs.edit().clear().apply()
    }
}


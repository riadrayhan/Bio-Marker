package com.riad.bio_marker.research

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.riad.bio_marker.core.ServiceLocator
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a single CSV bundle from:
 *   - per-day passive aggregates  (filesDir/aggregates/daily-*.json)
 *   - PHQ-9 / GAD-7 responses     (filesDir/surveys.jsonl)
 *
 * Output goes under filesDir/exports/ and is shared via FileProvider
 * (`<applicationId>.fileprovider`) so the user can hand it to a study coordinator.
 *
 * Personal data principle: the only identifier in the export is the anonymous
 * [StudyStore.participantId]. No phone number, name, IMEI, or location.
 */
object CsvExporter {

    private val tsFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun exportAndShare(context: Context): Intent {
        val file = buildCsv(context)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Bio_Marker research export")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun buildCsv(context: Context): File {
        val outDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "biomarker-export-${tsFmt.format(Date())}.csv")
        val pid = ServiceLocator.studyStore.participantId
        val enrolled = ServiceLocator.studyStore.enrolledAtMs

        outFile.bufferedWriter().use { w ->
            // Header.
            w.appendLine(
                listOf(
                    "participant_id",
                    "enrolled_at_ms",
                    "kind",
                    "date_or_ts",
                    "metric",
                    "value",
                ).joinToString(",")
            )

            // 1. Daily passive aggregates.
            val aggDir = File(context.filesDir, "aggregates")
            aggDir.listFiles { f -> f.name.startsWith("daily-") && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    val date = f.nameWithoutExtension.removePrefix("daily-")
                    runCatching {
                        val o = JSONObject(f.readText())
                        for (key in o.keys()) {
                            val raw = o.opt(key)?.toString() ?: continue
                            w.appendLine(csvRow(pid, enrolled, "passive", date, key, raw))
                        }
                    }
                }

            // 2. Surveys.
            ServiceLocator.surveyStore.allRecords().forEach { r ->
                w.appendLine(csvRow(pid, enrolled, "survey", r.timestampMs.toString(),
                    "${r.type.name}_total", r.total.toString()))
                r.answers.forEachIndexed { idx, v ->
                    w.appendLine(csvRow(pid, enrolled, "survey", r.timestampMs.toString(),
                        "${r.type.name}_q${idx + 1}", v.toString()))
                }
            }
        }
        return outFile
    }

    private fun csvRow(
        pid: String, enrolled: Long, kind: String, dateOrTs: String,
        metric: String, value: String,
    ): String = listOf(
        pid, enrolled.toString(), kind, dateOrTs, metric, value
    ).joinToString(",") { it.replace(",", " ").replace("\n", " ") }
}


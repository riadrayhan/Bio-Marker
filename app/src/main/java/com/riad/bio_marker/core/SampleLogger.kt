package com.riad.bio_marker.core

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only JSONL logger. Each day gets its own file under
 * <filesDir>/samples/samples-YYYY-MM-DD.jsonl. Encryption / Room
 * comes in Slice 1.5.
 */
class SampleLogger(private val context: Context) {

    private val dir: File = File(context.filesDir, "samples").apply { mkdirs() }
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lock = Any()

    fun appendMotion(timestampMs: Long, energy: Float, x: Float, y: Float, z: Float) {
        val line = """{"t":$timestampMs,"k":"motion","e":$energy,"x":$x,"y":$y,"z":$z}"""
        appendLine(timestampMs, line)
    }

    fun appendKeystroke(timestampMs: Long, latencyMs: Long, isBackspace: Boolean) {
        val line = """{"t":$timestampMs,"k":"key","lat":$latencyMs,"bs":$isBackspace}"""
        appendLine(timestampMs, line)
    }

    private fun appendLine(timestampMs: Long, line: String) {
        synchronized(lock) {
            val file = File(dir, "samples-${dateFmt.format(Date(timestampMs))}.jsonl")
            FileWriter(file, true).use { it.appendLine(line) }
        }
    }

    fun listFiles(): List<File> = dir.listFiles()?.sortedBy { it.name } ?: emptyList()

    /** Returns today's JSONL file, or null if no samples have been written yet. */
    fun todayFile(): File? {
        val f = File(dir, "samples-${dateFmt.format(Date())}.jsonl")
        return if (f.exists()) f else null
    }

    fun clearAll() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    fun deleteOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        synchronized(lock) {
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        }
    }
}


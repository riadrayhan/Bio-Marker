package com.riad.bio_marker.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Reads CallLog.Calls to estimate "talking time" used as a proxy for daily
 * social interaction.
 *
 * Permission: requires [Manifest.permission.READ_CALL_LOG] (declared in
 * AndroidManifest and requested via PermissionManager). If not granted,
 * [snapshotForToday]/[snapshotForDay] return null and the worker falls back
 * to 0 talking minutes (the rule engine will mark the metric as LESS).
 */
class CallLogCollector(private val context: Context) {

    data class DayCalls(
        val totalTalkSec: Long,
        val incomingSec: Long,
        val outgoingSec: Long,
        val callCount: Int,
    ) {
        val totalTalkMin: Int get() = (totalTalkSec / 60L).toInt()
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALL_LOG
    ) == PackageManager.PERMISSION_GRANTED

    fun snapshotForToday(): DayCalls? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val end = cal.timeInMillis
        return snapshotForDay(start, end)
    }

    /** Snapshot for the specific day [startMs, endMs). */
    fun snapshotForDay(startMs: Long, endMs: Long): DayCalls? {
        if (!hasPermission()) return null
        val cr = context.contentResolver
        val cols = arrayOf(CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.DATE)
        val sel = "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} < ?"
        val args = arrayOf(startMs.toString(), endMs.toString())
        val cursor = try {
            cr.query(CallLog.Calls.CONTENT_URI, cols, sel, args, null)
        } catch (_: SecurityException) { null } catch (_: Throwable) { null }
            ?: return null

        var total = 0L; var inc = 0L; var out = 0L; var n = 0
        cursor.use { c ->
            val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
            if (durIdx < 0 || typeIdx < 0) return DayCalls(0, 0, 0, 0)
            while (c.moveToNext()) {
                val d = c.getLong(durIdx)
                if (d <= 0) continue
                total += d; n += 1
                when (c.getInt(typeIdx)) {
                    CallLog.Calls.INCOMING_TYPE -> inc += d
                    CallLog.Calls.OUTGOING_TYPE -> out += d
                }
            }
        }
        return DayCalls(total, inc, out, n)
    }
}


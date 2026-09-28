package com.riad.bio_marker.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Single source of truth for runtime permissions + IME enablement.
 *
 * One-click flow:
 *  1. Request notification + microphone permissions in one [androidx.activity.result] call.
 *  2. If our IME isn't enabled, drop user into the system input-method settings.
 */
object PermissionManager {

    /** All runtime permissions we need at first launch. */
    fun runtimePermissionsToRequest(context: Context): Array<String> {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (!isGranted(context, Manifest.permission.RECORD_AUDIO))
            needed += Manifest.permission.RECORD_AUDIO
        // Camera — needed for the fingertip-PPG heart-rate check-in in
        // VitalsCheckInActivity. Without requesting it here the user only
        // sees the prompt the first time they tap "Vitals check-in".
        if (!isGranted(context, Manifest.permission.CAMERA))
            needed += Manifest.permission.CAMERA
        // Body sensors — for TYPE_HEART_RATE on Wear OS / Samsung devices.
        // Silent no-op on phones without the sensor; we still request so
        // paired wearables / future Health Connect bridges can deliver HR.
        if (!isGranted(context, Manifest.permission.BODY_SENSORS))
            needed += Manifest.permission.BODY_SENSORS
        // Without this on Android 10+ the step counter sensor silently
        // never fires — that's why "walking" was always 0.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !isGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)
        ) needed += Manifest.permission.ACTIVITY_RECOGNITION
        // Coarse location is needed to derive `location_entropy` (mobility).
        if (!isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION))
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        // Talking-time (call duration) for the daily-targets feature.
        if (!isGranted(context, Manifest.permission.READ_CALL_LOG))
            needed += Manifest.permission.READ_CALL_LOG
        return needed.toTypedArray()
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Whether our IME is in the system list of enabled input methods. */
    fun isImeEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val target = "${context.packageName}/.collectors.BioMarkerIME"
        return imm.enabledInputMethodList.any {
            it.id.contains(context.packageName) && it.id.contains("BioMarkerIME")
        } || imm.enabledInputMethodList.any { it.id == target }
    }

    /** Human-readable list of what is still missing. */
    fun missingLabels(context: Context): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) list += context.getString(com.riad.bio_marker.R.string.perm_notif)
        if (!isGranted(context, Manifest.permission.RECORD_AUDIO))
            list += context.getString(com.riad.bio_marker.R.string.perm_mic)
        if (!isGranted(context, Manifest.permission.CAMERA))
            list += "camera"
        if (!isGranted(context, Manifest.permission.BODY_SENSORS))
            list += "body sensors"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !isGranted(context, Manifest.permission.ACTIVITY_RECOGNITION)
        ) list += "activity"
        if (!isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION))
            list += "location"
        if (!isGranted(context, Manifest.permission.READ_CALL_LOG))
            list += "call log"
        if (!hasUsageStatsAccess(context))
            list += "usage access"
        if (!isImeEnabled(context))
            list += context.getString(com.riad.bio_marker.R.string.perm_ime)
        return list
    }

    fun openImeSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    /**
     * Usage Access is a *special* (non-runtime) permission: the user must
     * grant it in Settings → Apps → Special app access → Usage access.
     * We can only *check* the state via AppOpsManager.
     */
    fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as? android.app.AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** Opens the system Usage-Access settings page so the user can grant it. */
    fun openUsageAccessSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}


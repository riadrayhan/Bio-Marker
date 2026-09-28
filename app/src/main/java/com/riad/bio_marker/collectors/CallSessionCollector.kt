package com.riad.bio_marker.collectors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.riad.bio_marker.core.ConsentStore
import com.riad.bio_marker.core.DailyEvent
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.recordEvent
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Detects in-progress phone-call sessions by listening to TelephonyManager
 * call-state transitions:
 *
 *   IDLE  → OFFHOOK        → call started (record startMs)
 *   OFFHOOK → IDLE         → call ended   (record endMs, emit CALL event)
 *   IDLE  → RINGING        → ignore (only counted if it goes OFFHOOK)
 *
 * Unlike [CallLogCollector] (which is a once-a-day post-hoc reader), this
 * collector emits a live timeline event the moment a call ends, so the
 * Timeline UI can reflect calls within seconds.
 *
 * Permission: requires [Manifest.permission.READ_PHONE_STATE]. If not
 * granted, [start] returns false and we silently skip.
 *
 * NOTE: We never record phone numbers, contact names, or audio — only
 * (startMs, endMs, direction-unknown).
 */
class CallSessionCollector(private val context: Context) {

    private var tm: TelephonyManager? = null
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: Any? = null  // TelephonyCallback on API 31+
    private val executor: Executor = Executors.newSingleThreadExecutor()

    @Volatile private var callStartMs: Long = 0L

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "READ_PHONE_STATE not granted — call session events disabled")
            return false
        }
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        tm = manager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                manager.registerTelephonyCallback(executor, cb)
                modernCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        handleState(state)
                }
                @Suppress("DEPRECATION")
                manager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = l
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register call-state listener", t)
            false
        }
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modernCallback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Throwable) { /* ignore */ }
        modernCallback = null
        legacyListener = null
        tm = null
    }

    private fun handleState(state: Int) {
        val now = System.currentTimeMillis()
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (callStartMs == 0L) callStartMs = now
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val s = callStartMs
                if (s != 0L) {
                    callStartMs = 0L
                    val dur = now - s
                    if (dur >= MIN_CALL_MS) {
                        ServiceLocator.recordEvent(
                            type = DailyEvent.Type.CALL,
                            startMs = s,
                            endMs = now,
                            source = "telephony",
                            metadata = mapOf("duration_sec" to (dur / 1000L).toString()),
                            requiredSignal = ConsentStore.Signal.CALL_EVENTS,
                        )
                        Log.i(TAG, "CALL event emitted (${dur / 1000}s)")
                    }
                }
            }
            // RINGING is ignored — only counted if user picks up (→ OFFHOOK).
        }
    }

    companion object {
        private const val TAG = "CallSessionCollector"
        /** Ignore < 5 s "calls" (likely misdials). */
        private const val MIN_CALL_MS = 5_000L
    }
}


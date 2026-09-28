package com.riad.bio_marker.collectors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.riad.bio_marker.MainActivity
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ConsentStore
import com.riad.bio_marker.core.ServiceLocator

class BiomarkerService : LifecycleService() {

    private lateinit var sensorManager: SensorManager
    private var motionCollector: MotionCollector? = null
    private var stepCollector: StepCollector? = null
    private var locationCollector: LocationCollector? = null
    private var walkBoutDetector: WalkBoutDetector? = null
    private var callSessionCollector: CallSessionCollector? = null
    private var passiveVoiceCollector: PassiveVoiceCollector? = null
    private var heartRateCollector: HeartRateCollector? = null


    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate — booting biomarker collectors")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // Foreground notification first — required within ~5 s of startService.
        // If this throws, abort cleanly so the user sees a stop instead of a
        // zombie process that quietly never collects anything.
        try {
            startInForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed — aborting service", t)
            ServiceLocator.metricsBus.setServiceRunning(false)
            stopSelf()
            return
        }
        val consent = ServiceLocator.consentStore
        if (consent.isEnabled(ConsentStore.Signal.MOTION)) startMotion()
        if (consent.isEnabled(ConsentStore.Signal.MOTION)) startSteps()
        if (consent.isEnabled(ConsentStore.Signal.LOCATION)) startLocation()
        // Per-event timeline collectors. Each is gated by its own consent
        // signal inside start*(), so a user can run "step + sleep only"
        // without ever spinning these up.
        if (consent.isEnabled(ConsentStore.Signal.WALK_BOUTS)) startWalkBouts()
        if (consent.isEnabled(ConsentStore.Signal.CALL_EVENTS)) startCallSessions()
        // Heart-rate sensor (TYPE_HEART_RATE). Silent no-op on devices
        // without the sensor or without BODY_SENSORS permission. Augments
        // the on-demand camera-PPG flow with passive samples from paired
        // wearables / Galaxy phones.
        startHeartRate()
        // Continuous passive voice capture for the whole window. Gated on
        // permission inside start() — silently no-op if RECORD_AUDIO isn't
        // granted. The persistent foreground notification + Android privacy
        // indicators (orange dot) make active recording obvious to the user.
        startPassiveVoice()
        ServiceLocator.metricsBus.setServiceRunning(true)
        // First "point" of the timeline so Groq knows when monitoring started.
        ServiceLocator.activityPointLogger.log("service.start", "monitoring started")
        Log.i(TAG, "onCreate — done. motion=${motionCollector!=null} " +
            "steps=${stepCollector!=null} location=${locationCollector!=null} " +
            "walkBouts=${walkBoutDetector!=null} callSessions=${callSessionCollector!=null} " +
            "voice=${passiveVoiceCollector!=null}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startMotion() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            Log.w(TAG, "TYPE_ACCELEROMETER unavailable — motion collection disabled")
            return
        }
        motionCollector = MotionCollector(
            ServiceLocator.metricsBus,
            ServiceLocator.sampleLogger,
            ServiceLocator.userBaseline,
        ).also {
            val ok = sensorManager.registerListener(
                it,
                accel,
                SensorManager.SENSOR_DELAY_GAME // ~50Hz
            )
            Log.i(TAG, "registerListener(accelerometer) -> $ok")
        }
    }

    private fun startSteps() {
        // Android 10+ requires runtime ACTIVITY_RECOGNITION before the step
        // sensor will deliver any events. Without this, "walking" was always 0.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "ACTIVITY_RECOGNITION not granted — step counter disabled")
                return
            }
        }
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor == null) {
            Log.w(TAG, "TYPE_STEP_COUNTER unavailable on this device")
            return
        }
        stepCollector = StepCollector(ServiceLocator.metricsBus).also {
            val ok = sensorManager.registerListener(
                it, stepSensor, SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.i(TAG, "registerListener(stepCounter) -> $ok")
        }
    }

    private fun startLocation() {
        locationCollector = LocationCollector(applicationContext).also {
            // start() returns false if perms are missing — that's fine, we just
            // skip mobility features for the day rather than crashing.
            val ok = it.start()
            if (ok) {
                // Expose to DailyAggregationWorker so location_entropy is real.
                ServiceLocator.activeLocationCollector = it
            } else {
                Log.w(TAG, "LocationCollector did not start (missing perm or provider)")
            }
        }
    }

    private fun startWalkBouts() {
        walkBoutDetector = WalkBoutDetector(sensorManager).also {
            val ok = it.start()
            if (!ok) walkBoutDetector = null
        }
    }

    private fun startCallSessions() {
        // Permission may not be granted yet — collector handles that gracefully.
        callSessionCollector = CallSessionCollector(applicationContext).also {
            val ok = it.start()
            if (!ok) callSessionCollector = null
        }
    }


    private fun startPassiveVoice() {
        passiveVoiceCollector = PassiveVoiceCollector(applicationContext).also {
            val ok = it.start()
            if (!ok) passiveVoiceCollector = null
        }
    }


    private fun startHeartRate() {
        heartRateCollector = HeartRateCollector(applicationContext).also {
            val ok = it.start()
            if (ok) {
                ServiceLocator.activeHeartRateCollector = it
            } else {
                heartRateCollector = null
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Android 14+ (API 34) requires the foreground-service type to be
        // passed explicitly here, otherwise the OS throws and the service
        // dies. We declare every type the service actually needs:
        //   - HEALTH       : sensor / wellbeing monitoring (the primary use)
        //   - DATA_SYNC    : defensive fallback for older Android 14 devices
        //   - MICROPHONE   : continuous passive voice capture
        // Each type also requires the matching FOREGROUND_SERVICE_* perm in
        // the manifest, AND the runtime perm (RECORD_AUDIO) before
        // the relevant collector actually opens the hardware.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        motionCollector?.let { sensorManager.unregisterListener(it) }
        motionCollector = null
        stepCollector?.let { sensorManager.unregisterListener(it) }
        stepCollector = null
        locationCollector?.stop()
        locationCollector = null
        ServiceLocator.activeLocationCollector = null
        walkBoutDetector?.stop()
        walkBoutDetector = null
        callSessionCollector?.stop()
        callSessionCollector = null
        passiveVoiceCollector?.stop()
        passiveVoiceCollector = null
        heartRateCollector?.stop()
        heartRateCollector = null
        ServiceLocator.activeHeartRateCollector = null
        // Flush any in-progress typing session into the timeline.
        TypingSessionAggregator.flush()
        ServiceLocator.activityPointLogger.log("service.stop", "monitoring stopped")
        ServiceLocator.metricsBus.setServiceRunning(false)
        // If the user stops early (≥ 5 min into a window), still trigger the
        // AI analysis so they actually get a Groq verdict — without this the
        // worker only fires after `setInitialDelay(periodHours, HOURS)`,
        // which silently never happens for early-stopped sessions.
        // We run the SAME chain the timed path uses: MoodWindowEndWorker (which
        // sends the spoken-word transcripts to Groq, saves the voice mood
        // verdict, and notifies the user) → WindowAIAnalysisWorker (overall
        // window summary for the dashboard). This guarantees the "how was your
        // mood" result appears even when monitoring is stopped manually.
        try {
            val session = ServiceLocator.monitoringStore.current()
            if (session != null) {
                val now = System.currentTimeMillis()
                val elapsedMs = now - session.startedAtMs
                if (elapsedMs >= 5L * 60 * 1000) {
                    val periodH = ((elapsedMs / (60 * 60 * 1000L)).toInt()).coerceAtLeast(1)
                    val data = androidx.work.Data.Builder()
                        .putLong(
                            com.riad.bio_marker.worker.MoodWindowEndWorker.KEY_WINDOW_START,
                            session.startedAtMs,
                        )
                        .putLong(
                            com.riad.bio_marker.worker.MoodWindowEndWorker.KEY_WINDOW_END,
                            now,
                        )
                        .putInt(
                            com.riad.bio_marker.worker.WindowAIAnalysisWorker.KEY_PERIOD_HOURS,
                            periodH,
                        )
                        .build()
                    val moodReq = androidx.work.OneTimeWorkRequestBuilder<
                        com.riad.bio_marker.worker.MoodWindowEndWorker>()
                        .setInputData(data)
                        .build()
                    val aiReq = com.riad.bio_marker.worker.WindowAIAnalysisWorker.buildOneShot(
                        startMs = session.startedAtMs,
                        endMs = now,
                        periodHours = periodH,
                    )
                    androidx.work.WorkManager.getInstance(this)
                        .beginUniqueWork(
                            "mood_window_end",
                            androidx.work.ExistingWorkPolicy.REPLACE,
                            moodReq,
                        )
                        .then(aiReq)
                        .enqueue()
                    Log.i(TAG, "Enqueued early-stop mood+AI chain " +
                        "(elapsed=${elapsedMs / 60_000}m)")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "early-stop AI enqueue failed: ${t.message}")
        }
        super.onDestroy()
    }

    /** Exposed so the daily aggregator can read & reset mobility features. */
    fun snapshotLocation(): Pair<Float, Float>? {
        val lc = locationCollector ?: return null
        return lc.entropy() to lc.radiusKm()
    }

    fun resetLocation() {
        locationCollector?.reset()
    }

    companion object {
        private const val TAG = "BiomarkerService"
        private const val CHANNEL_ID = "biomarker_collection"
        private const val NOTIF_ID = 4711
        const val ACTION_STOP = "com.riad.bio_marker.STOP"

        fun start(context: Context) {
            val intent = Intent(context, BiomarkerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BiomarkerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}


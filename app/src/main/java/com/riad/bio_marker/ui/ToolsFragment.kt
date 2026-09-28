package com.riad.bio_marker.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.riad.bio_marker.R
import com.riad.bio_marker.collectors.BiomarkerService
import com.riad.bio_marker.collectors.VoiceCollector
import com.riad.bio_marker.collectors.VoiceTranscriber
import com.riad.bio_marker.core.PermissionManager
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.ml.VoiceFeatureCache
import com.riad.bio_marker.worker.DailyAggregationWorker
import com.riad.bio_marker.worker.MoodWindowEndWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToolsFragment : Fragment(R.layout.fragment_tools) {

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) runVoiceCheckIn()
            else Toast.makeText(requireContext(),
                R.string.dash_voice_perm_denied, Toast.LENGTH_SHORT).show()
        }

    /** One-shot multi-permission launcher used by "Start window" so we can ask
     *  for ACTIVITY_RECOGNITION + LOCATION + RECORD_AUDIO + POST_NOTIFICATIONS
     *  in a single dialog flow instead of forcing the user to hunt through
     *  Settings. Without this, step count / mobility never start working. */
    private val startSessionPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Whatever the user picked, proceed — collectors that lack a perm
            // will just no-op for that signal (graceful degradation).
            startMonitoringWindow()
        }


    private var selectedHours: Int = 1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val toggleBtn = view.findViewById<MaterialButton>(R.id.toggleBtn)
        val computeRiskBtn = view.findViewById<MaterialButton>(R.id.computeRiskBtn)
        val voiceCheckBtn = view.findViewById<MaterialButton>(R.id.voiceCheckBtn)
        val openImeSettingsBtn = view.findViewById<MaterialButton>(R.id.openImeSettingsBtn)
        val periodGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.periodGroup)
        val startSessionBtn = view.findViewById<MaterialButton>(R.id.startSessionBtn)
        val letsStartBtn = view.findViewById<MaterialButton>(R.id.letsStartBtn)
        val cancelSessionBtn = view.findViewById<MaterialButton>(R.id.cancelSessionBtn)
        val monitoringStatus = view.findViewById<TextView>(R.id.monitoringStatus)

        // Pre-select 1 hour.
        periodGroup.check(R.id.period1h)
        periodGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedHours = when (checkedId) {
                R.id.period1h -> 1
                R.id.period3h -> 3
                R.id.period1d -> 24
                R.id.period2d -> 48
                else          -> 1
            }
        }

        startSessionBtn.setOnClickListener {
            // Make sure every collector has the runtime perms it needs BEFORE
            // we boot the foreground service — otherwise step count / mobility
            // / mic just silently produce zeros (which was the user-visible
            // "nothing seems to work" bug).
            val needed = PermissionManager.runtimePermissionsToRequest(requireContext())
            if (needed.isNotEmpty()) {
                startSessionPermLauncher.launch(needed)
            } else {
                startMonitoringWindow()
            }
        }

        // Hero button toggles between "Let's start" and "Let's finish".
        // While a window is active it reads "Let's finish" so the user can
        // close the set early — which stops collection and immediately builds
        // the mood result from whatever voice + typing was captured.
        letsStartBtn.setOnClickListener {
            val session = ServiceLocator.monitoringStore.current()
            val active = session != null && !session.isExpired()
            if (active) finishMonitoringWindow()
            else startSessionBtn.performClick()
        }
        updateStartFinishButton()

        cancelSessionBtn.setOnClickListener {
            ServiceLocator.monitoringStore.cancel()
            ServiceLocator.metricsBus.bumpForUiRefresh()
            refreshMonitoringStatus(monitoringStatus)
            updateStartFinishButton()
        }

        toggleBtn.setOnClickListener {
            val running = ServiceLocator.metricsBus.state.value.serviceRunning
            if (running) BiomarkerService.stop(requireContext())
            else BiomarkerService.start(requireContext())
        }

        computeRiskBtn.setOnClickListener {
            val snapshot = ServiceLocator.metricsBus.state.value
            val result = ServiceLocator.riskScorer.score(snapshot)
            ServiceLocator.metricsBus.onRiskResult(result)
            Toast.makeText(requireContext(),
                "Updated", Toast.LENGTH_SHORT).show()
        }

        voiceCheckBtn.setOnClickListener {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
                runVoiceCheckIn()
            } else {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }


        // Manual entry-point for the same vitals (HR / BP estimate / breath
        // rate) check-in that the StressMonitorWorker auto-launches when it
        // detects a hyper / agitated state. Discoverable here so the user
        // can measure their pulse + BP estimate any time they want.
        view.findViewById<MaterialButton>(R.id.vitalsCheckBtn)?.setOnClickListener {
            startActivity(Intent(requireContext(), VitalsCheckInActivity::class.java))
        }

        // Surfaces every utterance recognised on-device during the current
        // monitoring window (Bangla + English) plus the latest Groq-generated
        // mood timeline ("good for 50 min, angry for last 10 min").
        view.findViewById<MaterialButton>(R.id.voiceNotesBtn)?.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceNotesActivity::class.java))
        }

        // Settings shortcut — the bottom bar now only shows 4 tabs, so the
        // Settings button moved here. We switch the hidden nav_settings
        // toggle to navigate to SettingsFragment via MainActivity's router.
        view.findViewById<MaterialButton>(R.id.openSettingsBtn)?.setOnClickListener {
            requireActivity()
                .findViewById<MaterialButtonToggleGroup>(R.id.navToggle)
                ?.check(R.id.nav_settings)
        }

        openImeSettingsBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            // Most users get lost between "enable in settings" and actually
            // *switching* to the new keyboard — surface the picker too.
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // In-app test field so the user can verify the IME without leaving the app.
        view.findViewById<MaterialButton>(R.id.switchKeyboardBtn)?.setOnClickListener {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        view.findViewById<EditText>(R.id.keyboardTestField)?.setOnClickListener {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Live label flips for Start/Stop, plus auto-rescore as new
        // keystrokes / motion accumulate so the user sees the score react
        // without having to repeatedly tap "Compute risk".
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastScoredKeys = -1
                ServiceLocator.metricsBus.state.collectLatest { s ->
                    toggleBtn.setText(
                        if (s.serviceRunning) R.string.dash_stop else R.string.dash_start
                    )
                    // Re-score every +20 keystrokes (and on the very first batch)
                    // so typing visibly moves the dashboard score.
                    if (lastScoredKeys < 0 || s.keystrokesToday - lastScoredKeys >= 20) {
                        lastScoredKeys = s.keystrokesToday
                        if (s.keystrokesToday > 0 || s.motionSamplesToday > 0) {
                            val r = ServiceLocator.riskScorer.score(s)
                            ServiceLocator.metricsBus.onRiskResult(r)
                        }
                    }
                    // Live "is this thing actually monitoring?" panel.
                    view.findViewById<TextView>(R.id.sensorHealth)
                        ?.let { renderSensorHealth(it, s) }
                }
            }
        }

        // Tick the monitoring status once per minute.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshMonitoringStatus(monitoringStatus)
                    delay(60_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<TextView>(R.id.monitoringStatus)
            ?.let { refreshMonitoringStatus(it) }
        updateStartFinishButton()
        // If a previous window has expired and produced an unseen mood verdict,
        // surface MoodSummaryActivity now so the user gets the "final output
        // when time ends" experience even if they missed the notification.
        maybeShowPendingMoodSummary()
    }

    private fun maybeShowPendingMoodSummary() {
        // Only auto-show once per app foreground to avoid being annoying.
        if (moodSummaryShownThisResume) return
        // The Groq voice-transcript verdict is the primary "how was your mood"
        // result. Surface it if the user hasn't seen it yet. Fall back to the
        // legacy face verdict for older windows.
        val hasVoiceVerdict =
            ServiceLocator.voiceTranscriptStore.unacknowledgedVerdict() != null
        val hasFaceVerdict = ServiceLocator.moodVerdictStore.load()
            ?.let { !it.acknowledged } ?: false
        if (!hasVoiceVerdict && !hasFaceVerdict) return
        moodSummaryShownThisResume = true
        startActivity(Intent(requireContext(), MoodSummaryActivity::class.java))
    }

    private var moodSummaryShownThisResume = false

    private fun scheduleMoodWindowEndWorker(periodHours: Int) {
        val session = ServiceLocator.monitoringStore.current() ?: return
        val data = Data.Builder()
            .putLong(MoodWindowEndWorker.KEY_WINDOW_START, session.startedAtMs)
            .putLong(MoodWindowEndWorker.KEY_WINDOW_END, session.endsAtMs)
            .putInt(com.riad.bio_marker.worker.WindowAIAnalysisWorker.KEY_PERIOD_HOURS, periodHours)
            .build()
        val moodReq = OneTimeWorkRequestBuilder<MoodWindowEndWorker>()
            .setInitialDelay(periodHours.toLong(), TimeUnit.HOURS)
            .setInputData(data)
            .build()
        // After the mood verdict is frozen, run the AI analysis worker with
        // a NETWORK_CONNECTED constraint + exponential backoff baked in so
        // a transient offline state doesn't silently lose the verdict.
        val aiReq = com.riad.bio_marker.worker.WindowAIAnalysisWorker.buildOneShot(
            startMs = session.startedAtMs,
            endMs = session.endsAtMs,
            periodHours = periodHours,
        )
        WorkManager.getInstance(requireContext())
            .beginUniqueWork("mood_window_end", ExistingWorkPolicy.REPLACE, moodReq)
            .then(aiReq)
            .enqueue()
    }

    private fun refreshMonitoringStatus(text: TextView) {        val session = ServiceLocator.monitoringStore.current()
        text.text = when {
            session == null -> getString(R.string.card_session_none)
            session.isExpired() -> getString(R.string.tools_session_finished)
            else -> {
                val ms = session.millisRemaining()
                val totalMin = ms / 60_000
                val days = totalMin / (60 * 24)
                val hours = (totalMin / 60) % 24
                val minutes = totalMin % 60
                val time = when {
                    days > 0  -> getString(R.string.time_d_h, days, hours)
                    hours > 0 -> getString(R.string.time_h_m, hours, minutes)
                    else      -> getString(R.string.time_m, minutes)
                }
                getString(R.string.tools_session_active, time)
            }
        }
    }

    private fun formatHours(hours: Int): String {
        return if (hours < 24) {
            val unit = getString(if (hours == 1) R.string.unit_hour else R.string.unit_hours)
            "$hours $unit"
        } else {
            val days = hours / 24
            val unit = getString(if (days == 1) R.string.unit_day else R.string.unit_days)
            "$days $unit"
        }
    }

    /**
     * Boot the monitoring window. Split out from the click handler so the
     * permission launcher callback can call it after the user grants perms.
     */
    private fun startMonitoringWindow() {
        // Hard pre-flight: without consent, BiomarkerService starts foreground
        // but registers ZERO collectors (every signal gate returns false), so
        // the user sees a persistent notification and motion/steps stay at 0.
        // Route to the consent screen instead of silently starting an empty
        // service.
        if (!ServiceLocator.consentStore.hasConsent) {
            Toast.makeText(requireContext(),
                R.string.tools_need_consent, Toast.LENGTH_LONG).show()
            startActivity(Intent(requireContext(), ConsentActivity::class.java))
            return
        }
        // Usage access is a *special* permission Android refuses to expose to
        // the runtime API — the user must flip it manually in Settings. Prompt
        // here so phone-usage / unlocks / social-app ratio actually populate.
        if (!PermissionManager.hasUsageStatsAccess(requireContext())) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.tools_usage_access_title)
                .setMessage(R.string.tools_usage_access_message)
                .setPositiveButton(R.string.tools_usage_access_open) { _, _ ->
                    PermissionManager.openUsageAccessSettings(requireActivity())
                }
                .setNegativeButton(R.string.tools_usage_access_skip) { _, _ ->
                    actuallyStartMonitoringWindow()
                }
                .show()
            return
        }
        actuallyStartMonitoringWindow()
    }

    private fun actuallyStartMonitoringWindow() {
        ServiceLocator.monitoringStore.start(selectedHours)
        BiomarkerService.start(requireContext())
        ServiceLocator.activityPointLogger.log(
            "window.start", "user started ${selectedHours}h window",
        )

        // Schedule the mood-window-end worker to run when the timer expires.
        // It will replay the per-window VoiceTranscriptStore entries through
        // Groq to produce a final mood verdict from speech.
        scheduleMoodWindowEndWorker(selectedHours)

        val snapshot = ServiceLocator.metricsBus.state.value
        val risk = ServiceLocator.riskScorer.score(snapshot)
        ServiceLocator.metricsBus.onRiskResult(risk)
        ServiceLocator.metricsBus.bumpForUiRefresh()

        // Build today's 15-feature daily vector NOW instead of waiting for
        // midnight — otherwise the user starts a window and sees most cards
        // still at zero, which feels broken.
        WorkManager.getInstance(requireContext()).enqueue(
            OneTimeWorkRequestBuilder<DailyAggregationWorker>().build()
        )

        // NOTE: we deliberately do NOT fire a one-shot voice check-in here.
        // The foreground BiomarkerService starts the continuous
        // PassiveVoiceCollector, which owns the microphone for the whole
        // window and writes every recognised utterance to the transcript
        // store. Launching a second AudioRecord + SpeechRecognizer at the
        // same instant made BOTH fail with ERROR_RECOGNIZER_BUSY / mic
        // contention — which is exactly why "voice wasn't working". The
        // manual "Voice check-in" button remains for ad-hoc prosody samples.

        ensureKeyboardActive()

        view?.findViewById<TextView>(R.id.monitoringStatus)
            ?.let { refreshMonitoringStatus(it) }
        Toast.makeText(requireContext(),
            getString(R.string.tools_session_active, formatHours(selectedHours)),
            Toast.LENGTH_SHORT).show()
        updateStartFinishButton()
    }

    /** Flips the hero button label between "Let's start" / "Let's finish"
     *  depending on whether a monitoring window is currently active. */
    private fun updateStartFinishButton() {
        val btn = view?.findViewById<MaterialButton>(R.id.letsStartBtn) ?: return
        val session = ServiceLocator.monitoringStore.current()
        val active = session != null && !session.isExpired()
        btn.setText(if (active) R.string.tools_lets_finish else R.string.tools_lets_start)
    }

    /**
     * User chose to close the set early. Stop collection and immediately
     * enqueue the same end-of-window mood + AI chain the timer would have
     * run, so they get their "how was your mood" result right away from the
     * voice + typing captured so far.
     */
    private fun finishMonitoringWindow() {
        val session = ServiceLocator.monitoringStore.current()
        if (session != null) {
            val now = System.currentTimeMillis()
            val periodH = ((now - session.startedAtMs) / (60 * 60 * 1000L))
                .toInt().coerceAtLeast(1)
            val data = Data.Builder()
                .putLong(MoodWindowEndWorker.KEY_WINDOW_START, session.startedAtMs)
                .putLong(MoodWindowEndWorker.KEY_WINDOW_END, now)
                .putInt(com.riad.bio_marker.worker.WindowAIAnalysisWorker.KEY_PERIOD_HOURS, periodH)
                .build()
            val moodReq = OneTimeWorkRequestBuilder<MoodWindowEndWorker>()
                .setInputData(data)
                .build()
            val aiReq = com.riad.bio_marker.worker.WindowAIAnalysisWorker.buildOneShot(
                startMs = session.startedAtMs,
                endMs = now,
                periodHours = periodH,
            )
            WorkManager.getInstance(requireContext())
                .beginUniqueWork("mood_window_end", ExistingWorkPolicy.REPLACE, moodReq)
                .then(aiReq)
                .enqueue()
        }
        // Stop the foreground collection service, then clear the window.
        BiomarkerService.stop(requireContext())
        ServiceLocator.monitoringStore.cancel()
        ServiceLocator.metricsBus.bumpForUiRefresh()
        Toast.makeText(requireContext(),
            R.string.tools_finished_toast, Toast.LENGTH_SHORT).show()
        view?.findViewById<TextView>(R.id.monitoringStatus)
            ?.let { refreshMonitoringStatus(it) }
        updateStartFinishButton()
    }


    /**
     * One-glance "is the system actually collecting?" panel. Shows live counters
     * for each of the 15 features so the user can see exactly which signals
     * are flowing and which are stuck (usually because of a missing permission).
     */
    private fun renderSensorHealth(
        target: TextView,
        s: com.riad.bio_marker.core.MetricsBus.Snapshot,
    ) {
        val voice = ServiceLocator.voiceCache.load()
        val today = ServiceLocator.dailyFeatureStore.load(
            ServiceLocator.dailyFeatureStore.todayKey()
        )
        fun ageSec(ts: Long?): String {
            if (ts == null || ts == 0L) return "never"
            val age = (System.currentTimeMillis() - ts) / 1000
            return when {
                age < 60 -> "${age}s ago"
                age < 3600 -> "${age / 60}m ago"
                else -> "${age / 3600}h ago"
            }
        }
        target.text = buildString {
            append("Service: ").append(if (s.serviceRunning) "ON ✓" else "OFF").append('\n')
            append("Motion samples: ").append(s.motionSamplesToday).append('\n')
            append("Steps today  : ").append(s.stepsToday).append('\n')
            append("Keystrokes   : ").append(s.keystrokesToday).append('\n')

            // Voice diagnostics — explain WHY voiceSentiment might be null.
            append("Voice last   : ").append(ageSec(voice?.timestampMs))
            if (voice != null && voice.f0Hz > 0f) {
                append(" · F0=").append("%.0f".format(voice.f0Hz)).append("Hz")
            }
            if (voice == null) {
                val micOk = ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                append(if (!micOk) " · ⚠ mic perm missing" else " · tap Voice check-in")
            } else if (!voice.hasTranscript) {
                append(" · ⚠ no transcript (offline STT?)")
            }
            append('\n')

            // Heart-rate sensor diagnostics. Most phones don't have a
            // hardware HR sensor — paired Wear OS / Samsung devices do.
            append("Heart rate   : ")
            val sm = requireContext().getSystemService(Context.SENSOR_SERVICE)
                as? android.hardware.SensorManager
            val hrSensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_HEART_RATE)
            val hrPermOk = ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
            val hr = ServiceLocator.activeHeartRateCollector?.stats()
            when {
                hrSensor == null -> append("no HR sensor on this device")
                !hrPermOk -> append("body-sensors permission needed")
                hr == null || hr.n == 0 -> append("waiting for samples…")
                else -> append("avg ${"%.0f".format(hr.avg ?: 0f)} bpm · n=${hr.n}")
            }
            append('\n')

            if (today != null) {
                append("Today vector :")
                append(" loc=").append("%.2f".format(today.locationEntropy))
                append(" sleep=").append("%.1fh".format(today.sleepDurationHrs))
                append(" screen=").append(today.screenTimeMin).append("m")
                append(" social=").append("%.2f".format(today.socialAppRatio))
                append('\n')
            } else {
                append("Today vector : not built yet — start a window\n")
            }
            // Surface missing permissions so the user knows why something is 0.
            val missing = PermissionManager.missingLabels(requireContext())
            if (missing.isNotEmpty()) {
                append("Missing perms: ").append(missing.joinToString(", "))
            }
        }
    }

    private fun runVoiceCheckIn() {
        Toast.makeText(requireContext(), R.string.dash_voice_recording, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            // 1) Acoustic features (5s) on a background thread.
            val result = withContext(Dispatchers.IO) {
                VoiceCollector().captureAndExtract(requireContext())
            }
            when (result) {
                is VoiceCollector.Result.Ok -> {
                    var snapshot = result.features
                    // 2) Speech-to-text + transcript analysis runs on the Main
                    //    looper (SpeechRecognizer needs a Looper). Best-effort
                    //    — a null result just means "no transcript today".
                    val analysis = try {
                        VoiceTranscriber.transcribeOnce(requireContext())
                    } catch (_: Throwable) { null }
                    if (analysis != null) snapshot = analysis.applyTo(snapshot)
                    ServiceLocator.voiceCache.save(snapshot)
                    ServiceLocator.activityPointLogger.log(
                        "checkin.voice",
                        "voice check-in (${snapshot.wordCount} words)",
                    )
                    val snap = ServiceLocator.metricsBus.state.value
                    val risk = ServiceLocator.riskScorer.score(snap)
                    ServiceLocator.metricsBus.onRiskResult(risk)
                    ServiceLocator.metricsBus.bumpForUiRefresh()
                    showVoiceResult(snapshot)
                }
                VoiceCollector.Result.PermissionDenied ->
                    Toast.makeText(requireContext(),
                        R.string.dash_voice_perm_denied, Toast.LENGTH_SHORT).show()
                VoiceCollector.Result.RecorderUnavailable ->
                    Toast.makeText(requireContext(),
                        R.string.dash_voice_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Auto-route the user to wherever they need to go to make the BioMarker
     * Auto-route the user to wherever they need to go to make the BioMarker
     * keyboard active — Android security forbids enabling an IME from code,
     * so the best we can do is one tap.
     *
     *  - If our IME isn't even in the user's "enabled" list, open the system
     *    Input-Method Settings (where they tick the toggle).
     *  - Otherwise, if it isn't the *current* IME, pop the input-method picker.
     *  - If it's already enabled and active, do nothing.
     */
    private fun ensureKeyboardActive() {
        val ctx = requireContext()
        val pkg = ctx.packageName
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val ourImeId = imm.enabledInputMethodList
            .firstOrNull { it.packageName == pkg }
            ?.id

        if (ourImeId == null) {
            // Not enabled yet — drop the user on the settings screen.
            Toast.makeText(ctx, R.string.tools_keyboard_enable_hint, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            return
        }

        val current = android.provider.Settings.Secure.getString(
            ctx.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        if (current != ourImeId) {
            // Enabled but not active — one-tap switch via the picker.
            Toast.makeText(ctx, R.string.tools_keyboard_switch_hint, Toast.LENGTH_LONG).show()
            imm.showInputMethodPicker()
        }
    }

    /**
     * Voice expressiveness 0..100 — higher = more dynamic prosody (less depressive).
     * Combines: normalised energy variability, low pause ratio, healthy ZCR.
     */
    private fun voiceWellness(s: VoiceFeatureCache.Snapshot): Int {
        val energyDynamic = (s.energyStd / 0.08f).coerceIn(0f, 1f)        // 0.08 ≈ healthy
        val notPaused = (1f - s.pauseRatio).coerceIn(0f, 1f)
        val zcr = (s.zcrMean / 0.15f).coerceIn(0f, 1f)
        val score = 0.45f * energyDynamic + 0.35f * notPaused + 0.20f * zcr
        return (score * 100f).toInt().coerceIn(0, 100)
    }

    private fun showVoiceResult(s: VoiceFeatureCache.Snapshot) {
        val ctx = context ?: return
        val detail = getString(
            R.string.dash_voice_done_detail,
            s.energyMean, s.energyStd, s.zcrMean, s.pauseRatio * 100f,
        )
        val wellness = getString(R.string.voice_wellness_score, voiceWellness(s))
        val pitch = if (s.f0Hz > 0f)
            "Pitch (F0): %.0f Hz · jitter %.2f%%".format(s.f0Hz, s.jitter * 100f)
        else "Pitch (F0): not detected"
        val text = if (s.hasTranscript) {
            val mood = when {
                s.sentimentScore > 0.05f -> "positive"
                s.sentimentScore < -0.05f -> "negative"
                else -> "neutral"
            }
            "Transcript: ${s.wordCount} words · " +
                "${s.hesitationCount} hesitations · " +
                "tone $mood (${"%+.2f".format(s.sentimentScore)})"
        } else {
            "Transcript: speech recognition unavailable"
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_result_title)
            .setMessage("$wellness\n\n$detail\n$pitch\n$text")
            .setPositiveButton(R.string.result_ok, null)
            .show()
    }
}

package com.riad.bio_marker.ui
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.riad.bio_marker.R
import com.riad.bio_marker.core.PpgHeartRateMeasurer
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.ml.GroqAgentClient
import kotlinx.coroutines.launch
/**
 * Vitals check-in: one button → live back-camera preview → 25-second PPG
 * capture → real heart-rate **and** blood-pressure estimate (same
 * fingertip-on-lens technique used by Samsung Health / Google Fit).
 *
 * Both numbers are persisted via [com.riad.bio_marker.core.BloodPressureStore]
 * so the end-of-window AI summary can include them in the report.
 *
 * The BP value is a transparent **estimate** computed from HR + HRV (RMSSD)
 * + perfusion amplitude — it is not medical-grade. A real cuff reading still
 * trumps it whenever the user enters one manually.
 */
class VitalsCheckInActivity : AppCompatActivity() {
    private var measuredHrBpm: Float? = null
    private var measuredBpSys: Int? = null
    private var measuredBpDia: Int? = null
    private lateinit var hrStatus: TextView
    private lateinit var bpStatus: TextView
    private lateinit var hrButton: Button
    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var actionsBox: LinearLayout
    private val hrMeasurer by lazy { PpgHeartRateMeasurer(this) }
    private var hrInProgress = false
    private val reasons: ArrayList<String> by lazy {
        intent?.getStringArrayListExtra(EXTRA_REASONS) ?: arrayListOf()
    }
    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startHrMeasurement()
            else Toast.makeText(this, R.string.dash_face_perm_denied, Toast.LENGTH_LONG).show()
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.vitals_title)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(sectionTitle(getString(R.string.vitals_hr_label)))
        val previewSize = (240 * resources.displayMetrics.density).toInt()
        previewView = PreviewView(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = ViewGroup.GONE
        }
        root.addView(previewView, LinearLayout.LayoutParams(previewSize, previewSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = pad / 2
            bottomMargin = pad / 2
        })
        hrStatus = label(getString(R.string.vitals_hr_idle), 16f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(hrStatus)
        bpStatus = label("", 14f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(bpStatus)
        hrButton = Button(this).apply {
            text = getString(R.string.vitals_hr_start)
            setOnClickListener { onHrButtonPressed() }
        }
        // Centered, content-width button as requested.
        root.addView(hrButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = pad / 2
        })
        val analyzeBtn = Button(this).apply {
            text = getString(R.string.vitals_analyze)
            setOnClickListener { runAnalyze() }
        }
        root.addView(analyzeBtn, params().apply { topMargin = pad })
        // Quick self-care actions (moved here from the Dashboard tab so they
        // sit alongside the live HR/BP capture they're meant to follow).
        val quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val breathingBtn = Button(this).apply {
            text = getString(R.string.me_action_breathing)
            setOnClickListener {
                startActivity(Intent(this@VitalsCheckInActivity, BreathingActivity::class.java))
            }
        }
        val therapistBtn = Button(this).apply {
            text = getString(R.string.me_action_therapist)
            setOnClickListener { openHelpline() }
        }
        val halfParam = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        quickActions.addView(breathingBtn, LinearLayout.LayoutParams(halfParam).apply { marginEnd = pad / 2 })
        quickActions.addView(therapistBtn, LinearLayout.LayoutParams(halfParam).apply { marginStart = pad / 2 })
        root.addView(quickActions, params().apply { topMargin = pad / 2 })
        resultText = label("", 13f).apply { setPadding(0, pad, 0, 0) }
        root.addView(resultText)
        actionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(actionsBox)
        // Pre-fill from the most recent PPG capture (if any) so the user can
        // re-use it in this session without re-measuring. Latest sensor-based
        // HR (HeartRateCollector / paired wearable) is the second-best
        // fallback when no PPG measurement exists yet.
        ServiceLocator.bloodPressureStore.latestHr()?.let {
            measuredHrBpm = it.bpm.toFloat()
            hrStatus.text = getString(R.string.vitals_hr_done, it.bpm)
            hrButton.text = getString(R.string.vitals_hr_redo)
        } ?: ServiceLocator.activeHeartRateCollector?.stats()?.avg
            ?.takeIf { it > 30f }?.let {
                measuredHrBpm = it
                hrStatus.text = getString(R.string.vitals_hr_done, it.toInt())
                hrButton.text = getString(R.string.vitals_hr_redo)
            }
        ServiceLocator.bloodPressureStore.latest()?.let {
            measuredBpSys = it.systolic
            measuredBpDia = it.diastolic
            bpStatus.text = getString(R.string.vitals_bp_done, it.systolic, it.diastolic)
        }
        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
        ServiceLocator.activityPointLogger.log("stress.checkin.open", "vitals check-in opened")
    }
    private fun onHrButtonPressed() {
        if (hrInProgress) { cancelHr(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA); return
        }
        startHrMeasurement()
    }
    private fun cancelHr() {
        hrMeasurer.stop()
        hrInProgress = false
        previewView.visibility = ViewGroup.GONE
        if (measuredHrBpm == null) {
            hrStatus.text = getString(R.string.vitals_hr_idle)
            hrButton.text = getString(R.string.vitals_hr_start)
        } else {
            hrButton.text = getString(R.string.vitals_hr_redo)
        }
    }
    private fun startHrMeasurement() {
        hrInProgress = true
        previewView.visibility = ViewGroup.VISIBLE
        // Countdown does NOT begin yet — we wait for the finger-on-lens
        // detector to fire onFingerState(true).
        hrStatus.text = getString(R.string.vitals_hr_place_finger)
        hrButton.text = getString(R.string.vitals_hr_cancel)
        val ok = hrMeasurer.start(this, object : PpgHeartRateMeasurer.Listener {
            override fun onFingerState(detected: Boolean) {
                runOnUiThread {
                    if (!hrInProgress) return@runOnUiThread
                    if (!detected) {
                        // Finger moved / lifted → measurer has discarded all
                        // samples and paused the 25-second timer. Tell the
                        // user; the count will restart from 0 once the
                        // finger is back in position.
                        hrStatus.text = getString(R.string.vitals_hr_place_finger)
                    }
                }
            }
            override fun onProgress(fraction: Float, livePulse: Float) {
                runOnUiThread {
                    if (!hrInProgress) return@runOnUiThread
                    // While paused (waiting for finger) the measurer reports
                    // fraction == 0f — keep the "place finger" prompt.
                    if (fraction <= 0f) return@runOnUiThread
                    val pct = (fraction * 100).toInt()
                    hrStatus.text = getString(R.string.vitals_hr_running, pct)
                }
            }
            override fun onResult(bpm: Float?, reason: String?) {
                runOnUiThread {
                    hrInProgress = false
                    previewView.visibility = ViewGroup.GONE
                    if (bpm != null) {
                        measuredHrBpm = bpm
                        hrStatus.text = getString(R.string.vitals_hr_done, bpm.toInt())
                        hrButton.text = getString(R.string.vitals_hr_redo)
                        // Persist so the AI window summary picks it up.
                        ServiceLocator.bloodPressureStore.saveHr(bpm.toInt())
                        ServiceLocator.activityPointLogger.log(
                            "stress.checkin.hr", "PPG HR=${bpm.toInt()} bpm",
                        )
                    } else {
                        hrStatus.text = reason ?: getString(R.string.vitals_hr_failed)
                        hrButton.text = getString(R.string.vitals_hr_start)
                    }
                }
            }
            override fun onBpEstimate(sys: Int?, dia: Int?, reason: String?) {
                runOnUiThread {
                    if (sys != null && dia != null) {
                        measuredBpSys = sys
                        measuredBpDia = dia
                        bpStatus.text = getString(R.string.vitals_bp_done, sys, dia)
                        ServiceLocator.bloodPressureStore.save(
                            sys, dia,
                            com.riad.bio_marker.core.BloodPressureStore.Source.PPG,
                        )
                        ServiceLocator.activityPointLogger.log(
                            "stress.checkin.bp", "PPG BP=$sys/$dia mmHg",
                        )
                    } else if (reason != null) {
                        bpStatus.text = getString(R.string.vitals_bp_low_conf)
                    }
                }
            }
        }, previewView)
        if (!ok) {
            hrInProgress = false
            previewView.visibility = ViewGroup.GONE
            hrStatus.text = getString(R.string.vitals_hr_no_camera)
            hrButton.text = getString(R.string.vitals_hr_start)
        }
    }
    private fun runAnalyze() {
        val hr = measuredHrBpm?.toInt()?.takeIf { it in 30..220 }
        val sys = measuredBpSys?.takeIf { it in 70..220 }
        val dia = measuredBpDia?.takeIf { it in 40..140 }
        if (hr == null && sys == null) {
            Toast.makeText(this, R.string.vitals_need_one, Toast.LENGTH_LONG).show()
            return
        }
        ServiceLocator.activityPointLogger.log(
            "stress.checkin.submit",
            "HR=${hr ?: "?"} BP=${sys ?: "?"}/${dia ?: "?"}",
        )
        resultText.text = getString(R.string.vitals_analyzing)
        actionsBox.removeAllViews()
        lifecycleScope.launch {
            val ai = runCatching {
                GroqAgentClient.suggestRelaxation(
                    heartRateBpm = hr,
                    bpSystolic = sys,
                    bpDiastolic = dia,
                    breathPerMin = null,
                    stressReasons = reasons,
                )
            }.getOrNull()
            if (ai == null) {
                resultText.text = getString(R.string.vitals_offline_fallback)
                addBreathingButton(); addCalmMusicButton()
                return@launch
            }
            val sb = StringBuilder()
            sb.append("• ").append(ai.headline).append("\n\n")
            if (ai.analysis.isNotBlank()) sb.append(ai.analysis).append("\n\n")
            ai.recommendations.forEach { sb.append("•  ").append(it).append('\n') }
            resultText.text = sb.toString().trim()
            addBreathingButton(); addCalmMusicButton()
        }
    }
    private fun addBreathingButton() {
        val btn = Button(this).apply {
            text = getString(R.string.vitals_action_breathing)
            setOnClickListener {
                startActivity(Intent(this@VitalsCheckInActivity, BreathingActivity::class.java))
            }
        }
        actionsBox.addView(btn, params())
    }
    private fun addCalmMusicButton() {
        val btn = Button(this).apply {
            text = getString(R.string.vitals_action_music)
            setOnClickListener {
                val q = Uri.encode("relaxing instrumental music")
                val tries = listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$q")),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")),
                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra("query", "relaxing instrumental music")
                    },
                )
                for (i in tries) {
                    if (i.resolveActivity(packageManager) != null) {
                        startActivity(i); return@setOnClickListener
                    }
                }
                Toast.makeText(this@VitalsCheckInActivity, R.string.vitals_no_music, Toast.LENGTH_SHORT).show()
            }
        }
        actionsBox.addView(btn, params())
    }
    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text; textSize = size
    }
    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun params(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
    override fun onDestroy() {
        hrMeasurer.stop()
        super.onDestroy()
    }
    private fun openHelpline() {
        // Kaan Pete Roi (Bangladesh): 24/7 emotional-support helpline.
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:01779554391")))
    }
    companion object {
        const val EXTRA_REASONS = "stress_reasons"
    }
}

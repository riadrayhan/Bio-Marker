package com.riad.bio_marker.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.riad.bio_marker.R

/**
 * 4-7-8 breathing exercise:
 *   - Inhale 4 s (circle scales up)
 *   - Hold   7 s (circle stays large)
 *   - Exhale 8 s (circle scales down)
 *
 * Default 4 rounds (~76 s total). Anxiety-attack-friendly: simple, no inputs.
 */
class BreathingActivity : AppCompatActivity() {

    private lateinit var circle: View
    private lateinit var phaseLabel: TextView
    private lateinit var countLabel: TextView
    private lateinit var roundLabel: TextView
    private var timer: CountDownTimer? = null
    private var animator: ObjectAnimator? = null
    private var round = 0
    private val totalRounds = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_breathing)
        circle = findViewById(R.id.breathCircle)
        phaseLabel = findViewById(R.id.breathPhase)
        countLabel = findViewById(R.id.breathCount)
        roundLabel = findViewById(R.id.breathRound)
        findViewById<Button>(R.id.breathStop).setOnClickListener { finish() }

        circle.scaleX = 0.6f
        circle.scaleY = 0.6f
        startNextPhase(Phase.INHALE)
    }

    private enum class Phase(val seconds: Int) { INHALE(4), HOLD(7), EXHALE(8) }

    private fun startNextPhase(phase: Phase) {
        roundLabel.text = getString(R.string.breath_round_n, round + 1, totalRounds)
        phaseLabel.text = getString(
            when (phase) {
                Phase.INHALE -> R.string.breath_phase_inhale
                Phase.HOLD -> R.string.breath_phase_hold
                Phase.EXHALE -> R.string.breath_phase_exhale
            }
        )
        animator?.cancel()
        when (phase) {
            Phase.INHALE -> animator = ObjectAnimator.ofFloat(circle, "scaleX", circle.scaleX, 1.4f)
                .apply { duration = phase.seconds * 1000L; start() }
                .also { ObjectAnimator.ofFloat(circle, "scaleY", circle.scaleY, 1.4f)
                    .apply { duration = phase.seconds * 1000L; start() } }
            Phase.EXHALE -> animator = ObjectAnimator.ofFloat(circle, "scaleX", circle.scaleX, 0.6f)
                .apply { duration = phase.seconds * 1000L; start() }
                .also { ObjectAnimator.ofFloat(circle, "scaleY", circle.scaleY, 0.6f)
                    .apply { duration = phase.seconds * 1000L; start() } }
            Phase.HOLD -> Unit
        }

        timer?.cancel()
        timer = object : CountDownTimer(phase.seconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                countLabel.text = ((msLeft / 1000L) + 1).toString()
            }
            override fun onFinish() {
                when (phase) {
                    Phase.INHALE -> startNextPhase(Phase.HOLD)
                    Phase.HOLD -> startNextPhase(Phase.EXHALE)
                    Phase.EXHALE -> {
                        round++
                        if (round >= totalRounds) {
                            phaseLabel.text = getString(R.string.breath_done_msg)
                            countLabel.text = ""
                        } else startNextPhase(Phase.INHALE)
                    }
                }
            }
        }.also { it.start() }
    }

    override fun onDestroy() {
        timer?.cancel()
        animator?.cancel()
        super.onDestroy()
    }
}


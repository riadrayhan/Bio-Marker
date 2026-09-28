package com.riad.bio_marker.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory bus for live UI metrics, updated by collectors.
 * Persistent storage is handled separately by [SampleLogger].
 */
class MetricsBus {

    data class Snapshot(
        val serviceRunning: Boolean = false,
        val motionSamplesToday: Int = 0,
        val recentMotionEnergy: Float = 0f,
        /** Cumulative motion-energy sum since last [resetDaily]. Used by
         *  the window estimator to compute a true *mean* energy over the
         *  entire window instead of relying on the latest sample only. */
        val motionEnergySumToday: Double = 0.0,
        /** Sum of energy^2 — combined with sum + count gives a true intra-day
         *  std without keeping every sample (Welford-equivalent). Used to fill
         *  feature 1 (`motion_energy_std`) of the daily 15-feature vector. */
        val motionEnergySumSqToday: Double = 0.0,
        val motionAnomaliesToday: Int = 0,
        val keystrokesToday: Int = 0,
        val backspacesToday: Int = 0,
        val avgInterKeyLatencyMs: Long = 0L,
        val stepsToday: Int = 0,
        val riskScore: Float? = null,
        val phq9Estimate: Int? = null,
        val riskTopContributors: List<String> = emptyList(),
        val riskEngineName: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun setServiceRunning(running: Boolean) =
        _state.update { it.copy(serviceRunning = running) }

    fun onMotionSample(energy: Float, anomalous: Boolean) =
        _state.update {
            it.copy(
                motionSamplesToday = it.motionSamplesToday + 1,
                recentMotionEnergy = energy,
                motionEnergySumToday = it.motionEnergySumToday + energy,
                motionEnergySumSqToday = it.motionEnergySumSqToday + energy.toDouble() * energy.toDouble(),
                motionAnomaliesToday = it.motionAnomaliesToday + if (anomalous) 1 else 0,
            )
        }

    fun onKeystroke(interKeyLatencyMs: Long, isBackspace: Boolean) =
        _state.update {
            val n = it.keystrokesToday + 1
            // Only fold non-zero latencies into the running mean — a 0 means
            // "first key in a session, no inter-key delta available".
            val newAvg = when {
                interKeyLatencyMs <= 0L -> it.avgInterKeyLatencyMs
                it.avgInterKeyLatencyMs == 0L -> interKeyLatencyMs
                else -> (it.avgInterKeyLatencyMs * it.keystrokesToday + interKeyLatencyMs) / n
            }
            it.copy(
                keystrokesToday = n,
                backspacesToday = it.backspacesToday + if (isBackspace) 1 else 0,
                avgInterKeyLatencyMs = newAvg,
            )
        }

    fun resetDaily() =
        _state.update {
            it.copy(
                motionSamplesToday = 0,
                motionEnergySumToday = 0.0,
                motionEnergySumSqToday = 0.0,
                motionAnomaliesToday = 0,
                keystrokesToday = 0,
                backspacesToday = 0,
                avgInterKeyLatencyMs = 0L,
                stepsToday = 0,
                riskScore = null,
                phq9Estimate = null,
                riskTopContributors = emptyList(),
            )
        }

    fun onStepCount(deltaStepsSinceServiceStart: Int) =
        _state.update { it.copy(stepsToday = deltaStepsSinceServiceStart) }

    fun onRiskResult(result: com.riad.bio_marker.ml.RiskResult) =
        _state.update {
            it.copy(
                riskScore = result.score,
                phq9Estimate = result.phq9Estimate,
                riskTopContributors = result.topContributors,
                riskEngineName = result.engineName,
            )
        }

    /**
     * Forces a StateFlow re-emission so observers (e.g. the dashboard) re-read
     * data that lives outside the snapshot — currently survey totals.
     * Cheap no-op copy.
     */
    fun bumpForUiRefresh() = _state.update { it.copy() }
}

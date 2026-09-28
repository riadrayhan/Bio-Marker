package com.riad.bio_marker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.ml.CrisisNotifier

/**
 * Periodic background risk scoring. Runs while the foreground service is also
 * collecting, so the dashboard always shows a fresh number even if the user
 * never taps "Compute risk".
 *
 * Schedule: every hour (set by [com.riad.bio_marker.BiomarkerApp]).
 */
class RiskComputationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!ServiceLocator.consentStore.hasConsent) return Result.success()
            val snapshot = ServiceLocator.metricsBus.state.value
            val result = ServiceLocator.riskScorer.score(snapshot)
            ServiceLocator.metricsBus.onRiskResult(result)
            CrisisNotifier(applicationContext).maybeNotify(result)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}


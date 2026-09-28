package com.riad.bio_marker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riad.bio_marker.core.ServiceLocator

/**
 * Enforces the spec retention policy:
 *   - Raw signals  : 7 days
 *   - Daily features: 30 days
 *
 * Runs once per day from [com.riad.bio_marker.BiomarkerApp].
 */
class RetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            ServiceLocator.sampleLogger.deleteOlderThan(RAW_DAYS)
            ServiceLocator.dailyFeatureStore.pruneOld(FEATURE_DAYS)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val RAW_DAYS = 7
        const val FEATURE_DAYS = 30
    }
}


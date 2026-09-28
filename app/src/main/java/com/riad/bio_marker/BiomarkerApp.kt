package com.riad.bio_marker

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.core.TextTranslator
import com.riad.bio_marker.worker.BedtimeReminderWorker
import com.riad.bio_marker.worker.DailyAggregationWorker
import com.riad.bio_marker.worker.DailySummaryWorker
import com.riad.bio_marker.worker.MLInferenceWorker
import com.riad.bio_marker.worker.NudgeWorker
import com.riad.bio_marker.worker.RetentionWorker
import com.riad.bio_marker.worker.RiskComputationWorker
import com.riad.bio_marker.worker.StressMonitorWorker
import java.util.concurrent.TimeUnit

class BiomarkerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyDefaultLocale()
        ServiceLocator.init(this)
        scheduleDailyAggregation()
        scheduleHourlyRiskScoring()
        scheduleNudges()
        scheduleRetention()
        scheduleNightlySummary()
        scheduleNightlyMLInference()
        scheduleStressMonitor()
        scheduleBedtimeReminder()
        // Pre-warm the on-device Bangla↔English translator so the first
        // tap on "🌐 Translate" is instant on devices that have already
        // cached the model. No-op on failure (model just downloads on
        // first interactive translate instead).
        TextTranslator.warmUp(this)
    }

    /**
     * Force Bangla as the in-app language on first launch so the localized
     * strings under `values-bn/` actually reach the user — most Bangladeshi
     * phones ship with the system locale set to English, which previously
     * caused the app to fall back to `values/strings.xml` even though a
     * full Bangla translation exists.
     *
     * We only set it when the user has *not yet* picked an explicit
     * per-app language (`AppCompatDelegate.getApplicationLocales().isEmpty`),
     * so anyone who later switches to English from the system Language
     * picker is respected and not overwritten on every cold start.
     *
     * Pairs with `android:localeConfig="@xml/locales_config"` in the
     * manifest, which lets Android 13+'s per-app language picker list
     * both English and Bangla.
     */
    private fun applyDefaultLocale() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags("bn")
            )
        }
    }

    // ...existing code...

    /**
     * Daily rule-based summary: target vs actual for sleep / walking / phone /
     * talking. Persisted by [DailySummaryWorker] into
     * [com.riad.bio_marker.core.DailySummaryStore] and surfaced by the
     * Summary tab on the next morning.
     */
    private fun scheduleNightlySummary() {
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily-summary",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Nightly on-device ML inference over the last 30
     * [com.riad.bio_marker.core.DailySummaryStore]
     * rows: anomaly detection, personalised target suggestions, weekly
     * pattern clustering. Output goes to [com.riad.bio_marker.core.MLInsightStore].
     */
    private fun scheduleNightlyMLInference() {
        val request = PeriodicWorkRequestBuilder<MLInferenceWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily-ml-insights",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleRetention() {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "retention-policy",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleDailyAggregation() {
        val request = PeriodicWorkRequestBuilder<DailyAggregationWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily-aggregation",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleHourlyRiskScoring() {
        val request = PeriodicWorkRequestBuilder<RiskComputationWorker>(
            1, TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "hourly-risk",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleNudges() {
        val request = PeriodicWorkRequestBuilder<NudgeWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "gentle-nudges-jitai",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Periodically (every 15 min — the WorkManager minimum) checks whether
     * the user looks visibly stressed. If so, [StressMonitorWorker] posts a
     * high-priority "📈 Quick vitals check?" notification that opens the
     * vitals check-in screen. Self-cooldown is 45 minutes; the worker also
     * exits early when no monitoring window is active so users aren't
     * notified outside of an opted-in session.
     */
    private fun scheduleStressMonitor() {
        val request = PeriodicWorkRequestBuilder<StressMonitorWorker>(
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "stress-monitor",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Bedtime nudge: every 15 minutes the worker checks the local clock.
     * Between 22:30 and 23:30 (and only while the user has an active
     * monitoring window) it fires up to two notifications per evening:
     * "sleep before 11 PM" and — if the user is on a social app right now —
     * "put the phone down 30 min before bed".
     */
    private fun scheduleBedtimeReminder() {
        val request = PeriodicWorkRequestBuilder<BedtimeReminderWorker>(
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BedtimeReminderWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}


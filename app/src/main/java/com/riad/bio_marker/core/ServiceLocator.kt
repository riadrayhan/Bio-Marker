package com.riad.bio_marker.core

import android.content.Context
import com.riad.bio_marker.ml.RiskScorer
import com.riad.bio_marker.ml.UserBaseline
import com.riad.bio_marker.ml.VoiceFeatureCache
import com.riad.bio_marker.monitoring.MonitoringStore
import com.riad.bio_marker.research.StudyStore
import com.riad.bio_marker.research.SurveyStore

/**
 * Lightweight manual DI container. Avoids pulling in Hilt for v0.1.
 */
object ServiceLocator {
    lateinit var appContext: Context
        private set
    lateinit var consentStore: ConsentStore
        private set
    lateinit var metricsBus: MetricsBus
        private set
    lateinit var sampleLogger: SampleLogger
        private set
    lateinit var userBaseline: UserBaseline
        private set
    lateinit var voiceCache: VoiceFeatureCache
        private set
    lateinit var surveyStore: SurveyStore
        private set
    lateinit var studyStore: StudyStore
        private set
    lateinit var monitoringStore: MonitoringStore
        private set
    lateinit var riskScorer: RiskScorer
        private set
    lateinit var dailyTrendStore: DailyTrendStore
        private set
    lateinit var nudgeStore: NudgeStore
        private set
    lateinit var moodGroundTruth: MoodGroundTruth
        private set
    lateinit var weeklyPatternStore: WeeklyPatternStore
        private set
    lateinit var dailyFeatureStore: DailyFeatureStore
        private set
    lateinit var riskHistoryStore: RiskHistoryStore
        private set
    lateinit var therapyReminder: TherapyReminder
        private set
    lateinit var userGoalStore: UserGoalStore
        private set
    lateinit var dailySummaryStore: DailySummaryStore
        private set
    lateinit var mlInsightStore: MLInsightStore
        private set
    lateinit var moodTimelineStore: MoodTimelineStore
        private set
    lateinit var moodVerdictStore: MoodVerdictStore
        private set
    lateinit var dailyEventStore: DailyEventStore
        private set
    lateinit var dailyReportStore: DailyReportStore
        private set
    lateinit var windowSummaryStore: WindowSummaryStore
        private set
    lateinit var activityPointLogger: ActivityPointLogger
        private set
    lateinit var bloodPressureStore: BloodPressureStore
        private set
    lateinit var voiceTranscriptStore: VoiceTranscriptStore
        private set

    /** Live, currently-running LocationCollector — set by BiomarkerService when
     *  it starts location collection. Read by DailyAggregationWorker so that
     *  `location_entropy` (feature 6) actually gets populated instead of 0. */
    @Volatile var activeLocationCollector: com.riad.bio_marker.collectors.LocationCollector? = null

    /** Live, currently-running HeartRateCollector — set by BiomarkerService when
     *  the BODY_SENSORS permission is granted and a TYPE_HEART_RATE sensor is
     *  present. Read by [WindowSummaryBuilder] when the monitoring window ends. */
    @Volatile var activeHeartRateCollector: com.riad.bio_marker.collectors.HeartRateCollector? = null

    /** True once [init] has run in this process — guards components (like the
     *  IME) that may spin up before/independently of the main UI. */
    fun isReady(): Boolean = ::appContext.isInitialized

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        consentStore = ConsentStore(appContext)
        metricsBus = MetricsBus()
        sampleLogger = SampleLogger(appContext)
        userBaseline = UserBaseline(appContext)
        voiceCache = VoiceFeatureCache(appContext)
        surveyStore = SurveyStore(appContext)
        studyStore = StudyStore(appContext)
        monitoringStore = MonitoringStore(appContext)
        riskScorer = RiskScorer(appContext, userBaseline, voiceCache)
        dailyTrendStore = DailyTrendStore(appContext)
        nudgeStore = NudgeStore(appContext)
        moodGroundTruth = MoodGroundTruth(appContext)
        weeklyPatternStore = WeeklyPatternStore(appContext)
        dailyFeatureStore = DailyFeatureStore(appContext)
        riskHistoryStore = RiskHistoryStore(appContext)
        therapyReminder = TherapyReminder(appContext)
        userGoalStore = UserGoalStore(appContext)
        dailySummaryStore = DailySummaryStore(appContext)
        mlInsightStore = MLInsightStore(appContext)
        moodTimelineStore = MoodTimelineStore(appContext)
        moodVerdictStore = MoodVerdictStore(appContext)
        dailyEventStore = DailyEventStore(appContext)
        dailyReportStore = DailyReportStore(appContext)
        windowSummaryStore = WindowSummaryStore(appContext)
        activityPointLogger = ActivityPointLogger(appContext)
        bloodPressureStore = BloodPressureStore(appContext)
        voiceTranscriptStore = VoiceTranscriptStore(appContext)
    }
}

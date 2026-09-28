package com.riad.bio_marker.rules
import android.content.Context
import com.riad.bio_marker.R
import com.riad.bio_marker.core.DailySummaryStore.OverallStatus
import com.riad.bio_marker.core.DailySummaryStore.Zone
/**
 * Pure rule-based daily evaluator (no ML, **no user-set targets**).
 *
 * The "set your own daily target" feature was removed: most users have no
 * idea what a healthy target *should* be, and chasing made-up numbers is
 * itself a stressor. Instead, we classify each metric against widely-cited
 * evidence-based ranges and let the AI agent
 * ([com.riad.bio_marker.ml.GroqAgentClient]) generate a personalised
 * recommendation on top.
 *
 * Healthy daily ranges (adults, derived from WHO / CDC / sleep-foundation
 * guidance):
 *   - **Sleep**   : 7-9 h.   < 7 h => deficient,  > 9 h => excessive.
 *   - **Walking** : 30-90 min of contiguous walking equivalent
 *                   (~ 7 000-10 000 steps).  < 30 min => low,  > 90 min => extra-active.
 *   - **Phone**   : 2-5 h non-call screen-time.  > 5 h => heavy,  < 2 h => light.
 *   - **Talking** : 15-90 min of voice interaction.
 */
object RuleEngine {
    enum class Metric { SLEEP, WALK, PHONE, TALK }
    private data class Band(val min: Float, val max: Float)
    private val BANDS = mapOf(
        Metric.SLEEP to Band(7f,   9f),       // hours
        Metric.WALK  to Band(30f,  90f),      // minutes
        Metric.PHONE to Band(2f,   5f),       // hours
        Metric.TALK  to Band(15f,  90f),      // minutes
    )
    data class MetricResult(
        val metric: Metric,
        val actual: Float,
        val healthyMin: Float,
        val healthyMax: Float,
        val zone: Zone,
        val isNegative: Boolean,
        val message: String,
    )
    data class Result(
        val sleep: MetricResult,
        val walk: MetricResult,
        val phone: MetricResult,
        val talk: MetricResult,
        val overall: OverallStatus,
        val headline: String,
    ) {
        fun all(): List<MetricResult> = listOf(sleep, walk, phone, talk)
        fun messages(): List<String> = all().map { it.message }
    }
    fun evaluate(
        ctx: Context,
        sleepHrs: Float,
        walkMin: Int,
        phoneHrs: Float,
        talkMin: Int,
    ): Result {
        val sleep = score(ctx, Metric.SLEEP, sleepHrs)
        val walk = score(ctx, Metric.WALK, walkMin.toFloat())
        val phone = score(ctx, Metric.PHONE, phoneHrs)
        val talk = score(ctx, Metric.TALK, talkMin.toFloat())
        val negatives = listOf(sleep, walk, phone, talk).count { it.isNegative }
        val overall = when {
            negatives == 0 -> OverallStatus.GOOD
            negatives >= 2 -> OverallStatus.ATTENTION
            else -> OverallStatus.MIXED
        }
        val headline = ctx.getString(
            when (overall) {
                OverallStatus.GOOD -> R.string.summary_overall_good
                OverallStatus.MIXED -> R.string.summary_overall_mixed
                OverallStatus.ATTENTION -> R.string.summary_overall_attention
            }
        )
        return Result(sleep, walk, phone, talk, overall, headline)
    }
    private fun score(ctx: Context, m: Metric, actual: Float): MetricResult {
        val band = BANDS.getValue(m)
        val zone = when {
            actual < band.min -> Zone.LESS
            actual > band.max -> Zone.MORE
            else -> Zone.NORMAL
        }
        val isNegative = when (m) {
            Metric.PHONE -> zone == Zone.MORE
            else -> zone == Zone.LESS
        }
        val msg = ctx.getString(messageRes(m, zone), formatActual(m, actual))
        return MetricResult(
            metric = m,
            actual = actual,
            healthyMin = band.min,
            healthyMax = band.max,
            zone = zone,
            isNegative = isNegative,
            message = msg,
        )
    }
    private fun messageRes(m: Metric, z: Zone): Int = when (m to z) {
        Metric.SLEEP to Zone.LESS -> R.string.rule_sleep_less
        Metric.SLEEP to Zone.NORMAL -> R.string.rule_sleep_normal
        Metric.SLEEP to Zone.MORE -> R.string.rule_sleep_more
        Metric.WALK to Zone.LESS -> R.string.rule_walk_less
        Metric.WALK to Zone.NORMAL -> R.string.rule_walk_normal
        Metric.WALK to Zone.MORE -> R.string.rule_walk_more
        Metric.PHONE to Zone.LESS -> R.string.rule_phone_less
        Metric.PHONE to Zone.NORMAL -> R.string.rule_phone_normal
        Metric.PHONE to Zone.MORE -> R.string.rule_phone_more
        Metric.TALK to Zone.LESS -> R.string.rule_talk_less
        Metric.TALK to Zone.NORMAL -> R.string.rule_talk_normal
        Metric.TALK to Zone.MORE -> R.string.rule_talk_more
        else -> R.string.rule_phone_normal
    }
    private fun formatActual(m: Metric, actual: Float): String = when (m) {
        Metric.SLEEP, Metric.PHONE -> "%.1f".format(actual)
        Metric.WALK, Metric.TALK -> actual.toInt().toString()
    }
}

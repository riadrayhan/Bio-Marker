package com.riad.bio_marker.core

import android.content.Context
import com.riad.bio_marker.R

/**
 * Maps an internal depression / anxiety score to a positive 0..100 wellness
 * index used by the "Me" audience tab (higher = healthier), with a verdict
 * label like the user-attached design ("চমৎকার", "ভালো", "মাঝামাঝি", …).
 */
object WellnessIndex {

    enum class Band { EXCELLENT, GOOD, MODERATE, LOW, ALERT }

    /** [phq9] in 0..27. Returns 0..100 where 100 = best. */
    fun fromPhq9(phq9: Int): Int =
        ((1f - (phq9.coerceIn(0, 27) / 27f)) * 100f).toInt().coerceIn(0, 100)

    fun fromBlended(depBlended: Float, anxBlended: Float): Int {
        // The user feels best when both depression and anxiety signals are low,
        // so take the worse of the two and invert.
        val worse = maxOf(depBlended, anxBlended).coerceIn(0f, 1f)
        return ((1f - worse) * 100f).toInt().coerceIn(0, 100)
    }

    fun band(score: Int): Band = when {
        score >= 80 -> Band.EXCELLENT
        score >= 60 -> Band.GOOD
        score >= 40 -> Band.MODERATE
        score >= 20 -> Band.LOW
        else        -> Band.ALERT
    }

    fun label(context: Context, band: Band): String = context.getString(
        when (band) {
            Band.EXCELLENT -> R.string.well_excellent
            Band.GOOD -> R.string.well_good
            Band.MODERATE -> R.string.well_moderate
            Band.LOW -> R.string.well_low
            Band.ALERT -> R.string.well_alert
        }
    )
}


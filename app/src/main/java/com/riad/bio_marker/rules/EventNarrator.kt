package com.riad.bio_marker.rules

import com.riad.bio_marker.core.AppCategoryMap
import com.riad.bio_marker.core.DailyEvent
import com.riad.bio_marker.core.DailyReportStore.EventNote
import com.riad.bio_marker.core.DailyReportStore.EventNote.Severity

/**
 * Turns one [DailyEvent] into a short, human-readable health note.
 *
 * The notes are intentionally conservative: only flag something as ATTENTION
 * if the duration crosses a clearly-evidenced threshold (e.g. continuous
 * phone call > 60 min, video apps > 2 h). Otherwise return INFO ("you walked
 * 35 min — nice").
 *
 * Strings are produced in English; localisation can be layered on later by
 * mapping (Type, Severity, durationBucket) → string-resource id.
 */
object EventNarrator {

    fun narrate(event: DailyEvent): EventNote {
        val mins = event.durationMin
        val mood = event.moodLabel
        return when (event.type) {
            DailyEvent.Type.WALK -> walkNote(mins)
            DailyEvent.Type.CALL -> callNote(mins, mood)
            DailyEvent.Type.APP_USE -> appNote(event, mins)
            DailyEvent.Type.TYPING -> typingNote(event, mins, mood)
            DailyEvent.Type.MEETING -> meetingNote(mins, mood)
            DailyEvent.Type.SLEEP -> sleepNote(mins)
        }.let { (text, sev) ->
            EventNote(
                eventId = event.id,
                eventType = event.type.name,
                note = text,
                severity = sev,
            )
        }
    }

    private fun walkNote(mins: Int): Pair<String, Severity> = when {
        mins >= 60 -> "Nice — you walked continuously for $mins min. Great for cardiovascular health." to Severity.POSITIVE
        mins >= 20 -> "You walked $mins min in one stretch — keep it up." to Severity.POSITIVE
        else -> "Short walk: $mins min." to Severity.INFO
    }

    private fun callNote(mins: Int, mood: String?): Pair<String, Severity> {
        val moodTag = moodSuffix(mood)
        return when {
            mins >= 60 -> "You talked on the phone for $mins min$moodTag. Continuous talking that long can be tiring — try a short break and some water." to Severity.ATTENTION
            mins >= 25 -> "Phone call: $mins min$moodTag." to Severity.INFO
            else -> "Short call ($mins min)$moodTag." to Severity.INFO
        }
    }

    private fun appNote(event: DailyEvent, mins: Int): Pair<String, Severity> {
        val pkg = event.metadata["package"] ?: ""
        val cat = runCatching {
            AppCategoryMap.Category.valueOf(event.metadata["category"] ?: "OTHER")
        }.getOrDefault(AppCategoryMap.Category.OTHER)
        val name = event.metadata["app_name"] ?: AppCategoryMap.displayName(pkg)

        return when (cat) {
            AppCategoryMap.Category.SOCIAL -> when {
                mins >= 60 -> "You spent $mins min on $name. Long social-media stretches can affect mood and eye strain — consider a 10-min break." to Severity.ATTENTION
                mins >= 20 -> "Social: $name for $mins min." to Severity.INFO
                else -> "$name for $mins min." to Severity.INFO
            }
            AppCategoryMap.Category.VIDEO -> when {
                mins >= 90 -> "You watched $name for $mins min. Long video sessions are hard on your eyes — try the 20-20-20 rule." to Severity.ATTENTION
                mins >= 30 -> "$name for $mins min." to Severity.INFO
                else -> "Quick $name session ($mins min)." to Severity.INFO
            }
            AppCategoryMap.Category.GAME -> when {
                mins >= 60 -> "Gaming on $name for $mins min — remember to stretch and rest your eyes." to Severity.ATTENTION
                else -> "Gaming on $name ($mins min)." to Severity.INFO
            }
            AppCategoryMap.Category.MESSAGING -> "Messaging on $name for $mins min." to Severity.INFO
            AppCategoryMap.Category.PRODUCTIVITY -> "Focused on $name for $mins min." to Severity.POSITIVE
            AppCategoryMap.Category.BROWSER -> "Browsing on $name for $mins min." to Severity.INFO
            AppCategoryMap.Category.OTHER -> "$name for $mins min." to Severity.INFO
        }
    }

    private fun typingNote(event: DailyEvent, mins: Int, mood: String?): Pair<String, Severity> {
        val chars = event.metadata["chars"]?.toIntOrNull() ?: 0
        val moodTag = when (mood) {
            "STRESSED" -> " You seemed a bit agitated while typing (lots of corrections)."
            "NEUTRAL", "HAPPY" -> " The typing rhythm looked steady — calm mood."
            else -> ""
        }
        return "Typed for $mins min ($chars characters).$moodTag" to Severity.INFO
    }

    private fun meetingNote(mins: Int, mood: String?): Pair<String, Severity> {
        val moodTag = moodSuffix(mood)
        return when {
            mins >= 60 -> "Meeting / conversation lasted $mins min$moodTag. Long meetings can be draining — take 5 min to decompress." to Severity.ATTENTION
            else -> "Meeting / conversation: $mins min$moodTag." to Severity.INFO
        }
    }

    private fun sleepNote(mins: Int): Pair<String, Severity> {
        val hrs = mins / 60f
        return when {
            hrs < 6f -> "You slept ${"%.1f".format(hrs)} h — under-slept. Try an earlier bedtime tonight." to Severity.ATTENTION
            hrs in 6f..9f -> "You slept ${"%.1f".format(hrs)} h — healthy range." to Severity.POSITIVE
            else -> "You slept ${"%.1f".format(hrs)} h — long sleep can sometimes signal fatigue." to Severity.INFO
        }
    }

    private fun moodSuffix(mood: String?): String = when (mood) {
        "HAPPY" -> " — you seemed in a good mood"
        "STRESSED" -> " — you seemed tense"
        "SAD" -> " — you seemed down"
        "TIRED" -> " — you looked tired"
        else -> ""
    }
}


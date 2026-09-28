package com.riad.bio_marker.core

import android.content.Context
import org.json.JSONObject

/**
 * Persists the user's daily targets:
 *  - sleep hours (default 8)
 *  - walking minutes (default 30)
 *  - phone usage hours (default 3)
 *  - talking minutes (default 20)
 *
 * Stored as a single JSON file under filesDir/user_goals.json. Matches the
 * project's existing file-store pattern (no Room dependency added on purpose).
 */
class UserGoalStore(context: Context) {

    private val file = java.io.File(context.filesDir, "user_goals.json")

    data class Goal(
        val sleepHours: Float = 8f,
        val walkingMinutes: Int = 30,
        val phoneUsageHours: Float = 3f,
        val talkingMinutes: Int = 20,
        val updatedAt: Long = 0L,
        val configured: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("sleep_hours", sleepHours.toDouble())
            put("walking_minutes", walkingMinutes)
            put("phone_usage_hours", phoneUsageHours.toDouble())
            put("talking_minutes", talkingMinutes)
            put("updated_at", updatedAt)
            put("configured", configured)
        }

        companion object {
            fun fromJson(o: JSONObject): Goal = Goal(
                sleepHours = o.optDouble("sleep_hours", 8.0).toFloat(),
                walkingMinutes = o.optInt("walking_minutes", 30),
                phoneUsageHours = o.optDouble("phone_usage_hours", 3.0).toFloat(),
                talkingMinutes = o.optInt("talking_minutes", 20),
                updatedAt = o.optLong("updated_at", 0L),
                configured = o.optBoolean("configured", false),
            )
        }
    }

    fun load(): Goal {
        if (!file.exists()) return Goal()
        return try {
            Goal.fromJson(JSONObject(file.readText()))
        } catch (_: Exception) {
            Goal()
        }
    }

    fun save(goal: Goal) {
        val stamped = goal.copy(updatedAt = System.currentTimeMillis(), configured = true)
        file.writeText(stamped.toJson().toString())
    }

    fun isConfigured(): Boolean = load().configured
}


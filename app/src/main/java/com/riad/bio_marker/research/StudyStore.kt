package com.riad.bio_marker.research

import android.content.Context
import java.util.UUID

/**
 * Anonymous study participant ID. Generated once on first launch, stored locally,
 * never tied to any PII. Re-rolled on consent revoke.
 */
class StudyStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("biomarker_study", Context.MODE_PRIVATE)

    val participantId: String
        get() {
            val existing = prefs.getString(K_ID, null)
            if (existing != null) return existing
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(K_ID, fresh).apply()
            return fresh
        }

    val enrolledAtMs: Long
        get() {
            var t = prefs.getLong(K_ENROLLED, 0L)
            if (t == 0L) {
                t = System.currentTimeMillis()
                prefs.edit().putLong(K_ENROLLED, t).apply()
            }
            return t
        }

    fun reset() = prefs.edit().clear().apply()

    companion object {
        private const val K_ID = "participant_id"
        private const val K_ENROLLED = "enrolled_at"
    }
}


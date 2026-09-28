package com.riad.bio_marker.research

/**
 * Validated screening instruments. Question text is intentionally English-only
 * for v1 — translation pass will land in Slice 7 (i18n).
 *
 * Both follow the standard 0..3 Likert scale:
 *   0 = Not at all     1 = Several days
 *   2 = More than half 3 = Nearly every day
 */
enum class SurveyType(
    val displayName: String,
    val questions: List<String>,
    val maxTotal: Int,
) {
    PHQ9(
        displayName = "PHQ-9 (depression screening)",
        questions = listOf(
            "Little interest or pleasure in doing things",
            "Feeling down, depressed, or hopeless",
            "Trouble falling/staying asleep, or sleeping too much",
            "Feeling tired or having little energy",
            "Poor appetite or overeating",
            "Feeling bad about yourself — or that you are a failure or have let yourself or your family down",
            "Trouble concentrating on things, such as reading or watching TV",
            "Moving or speaking so slowly that others have noticed; or being so fidgety/restless that you have been moving around a lot more than usual",
            "Thoughts that you would be better off dead, or thoughts of hurting yourself in some way",
        ),
        maxTotal = 27,
    ),
    GAD7(
        displayName = "GAD-7 (anxiety screening)",
        questions = listOf(
            "Feeling nervous, anxious, or on edge",
            "Not being able to stop or control worrying",
            "Worrying too much about different things",
            "Trouble relaxing",
            "Being so restless that it is hard to sit still",
            "Becoming easily annoyed or irritable",
            "Feeling afraid as if something awful might happen",
        ),
        maxTotal = 21,
    );

    val likertOptions: List<String> = listOf(
        "Not at all",
        "Several days",
        "More than half the days",
        "Nearly every day",
    )
}


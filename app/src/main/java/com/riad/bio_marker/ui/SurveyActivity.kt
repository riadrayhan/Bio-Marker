package com.riad.bio_marker.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.research.SurveyType

/**
 * Generic Likert-survey activity. Pass [EXTRA_TYPE] = [SurveyType.name] in the
 * launching intent. Builds the form programmatically so we don't ship a layout
 * per survey type — the question list comes from [SurveyType.questions].
 */
class SurveyActivity : AppCompatActivity() {

    private lateinit var type: SurveyType
    private val groups = mutableListOf<RadioGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = SurveyType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: SurveyType.PHQ9.name)
        title = type.displayName

        val pad = (resources.displayMetrics.density * 16).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "Over the last 2 weeks, how often have you been bothered by:"
            textSize = 16f
            setPadding(0, 0, 0, pad)
        })

        type.questions.forEachIndexed { idx, q ->
            column.addView(TextView(this).apply {
                text = "${idx + 1}. $q"
                setPadding(0, pad, 0, pad / 2)
            })
            val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            type.likertOptions.forEachIndexed { score, label ->
                rg.addView(RadioButton(this).apply {
                    text = "$score — $label"
                    id = score + 1   // 1..4 → 0..3
                })
            }
            groups += rg
            column.addView(rg)
        }

        column.addView(Button(this).apply {
            text = "Submit"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad * 2 }
            setOnClickListener { submit() }
        })

        setContentView(ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })
    }

    private fun submit() {
        val answers = groups.map { rg -> (rg.checkedRadioButtonId - 1).coerceAtLeast(-1) }
        if (answers.any { it < 0 }) {
            Toast.makeText(this, "Please answer every question.", Toast.LENGTH_SHORT).show()
            return
        }
        val rec = ServiceLocator.surveyStore.record(type, answers)
        Toast.makeText(this, "${type.displayName}: ${rec.total} / ${type.maxTotal}", Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_TYPE = "survey_type"
    }
}


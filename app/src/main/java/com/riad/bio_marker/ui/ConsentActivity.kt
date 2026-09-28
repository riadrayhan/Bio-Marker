package com.riad.bio_marker.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import com.riad.bio_marker.MainActivity
import com.riad.bio_marker.R
import com.riad.bio_marker.core.ServiceLocator

class ConsentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If consent is already granted, jump straight to the dashboard.
        if (ServiceLocator.consentStore.hasConsent) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_consent)

        val checkbox = findViewById<CheckBox>(R.id.consentCheckbox)
        val continueBtn = findViewById<Button>(R.id.consentContinue)

        checkbox.setOnCheckedChangeListener { _, checked ->
            continueBtn.isEnabled = checked
        }

        continueBtn.setOnClickListener {
            ServiceLocator.consentStore.hasConsent = true
            ServiceLocator.consentStore.consentTimestamp = System.currentTimeMillis()
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}


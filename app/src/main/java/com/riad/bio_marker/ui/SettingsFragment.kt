package com.riad.bio_marker.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.riad.bio_marker.MainActivity
import com.riad.bio_marker.R
import com.riad.bio_marker.collectors.BiomarkerService
import com.riad.bio_marker.core.PermissionManager
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.research.CsvExporter

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private lateinit var permStatusText: TextView

    private val multiPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Whatever the user picked, refresh status. If IME still not enabled, route there.
        refreshPermStatus()
        if (!PermissionManager.isImeEnabled(requireContext())) {
            PermissionManager.openImeSettings(requireActivity())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        permStatusText = view.findViewById(R.id.permStatusText)
        val grantAllBtn = view.findViewById<Button>(R.id.grantAllBtn)
        val participantIdText = view.findViewById<TextView>(R.id.participantIdText)
        val exportCsvBtn = view.findViewById<Button>(R.id.exportCsvBtn)
        val revokeBtn = view.findViewById<Button>(R.id.revokeBtn)

        participantIdText.text = getString(
            R.string.dash_participant_id,
            ServiceLocator.studyStore.participantId.take(8) + "…",
        )

        wireLanguageToggle(view)

        grantAllBtn.setOnClickListener {
            val needed = PermissionManager.runtimePermissionsToRequest(requireContext())
            if (needed.isNotEmpty()) {
                multiPermLauncher.launch(needed)
            } else if (!PermissionManager.isImeEnabled(requireContext())) {
                PermissionManager.openImeSettings(requireActivity())
            } else {
                refreshPermStatus()
            }
        }

        exportCsvBtn.setOnClickListener {
            val intent = CsvExporter.exportAndShare(requireContext())
            startActivity(Intent.createChooser(intent, getString(R.string.dash_export_share)))
        }

        revokeBtn.setOnClickListener {
            BiomarkerService.stop(requireContext())
            ServiceLocator.sampleLogger.clearAll()
            ServiceLocator.userBaseline.clear()
            ServiceLocator.voiceCache.clear()
            ServiceLocator.surveyStore.clear()
            ServiceLocator.studyStore.reset()
            ServiceLocator.monitoringStore.cancel()
            ServiceLocator.dailyFeatureStore.clearAll()
            ServiceLocator.riskHistoryStore.clear()
            ServiceLocator.therapyReminder.clear()
            ServiceLocator.consentStore.revoke()
            ServiceLocator.metricsBus.resetDaily()
            startActivity(
                Intent(requireContext(), ConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            (requireActivity() as? MainActivity)?.finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermStatus()
    }

    private fun refreshPermStatus() {
        val missing = PermissionManager.missingLabels(requireContext())
        permStatusText.text = if (missing.isEmpty())
            getString(R.string.perm_status_all)
        else
            getString(R.string.perm_status_missing, missing.joinToString(", "))
    }

    /**
     * Two-button (English / বাংলা) language switcher backed by
     * [AppCompatDelegate.setApplicationLocales]. The choice is persisted
     * by AndroidX itself (so it survives process death and reboots) and
     * is also reflected in the system per-app language picker on
     * Android 13+ thanks to `android:localeConfig` in the manifest.
     *
     * The currently active language is preselected on the toggle group
     * so the user always sees the truth, not just whatever they last
     * tapped. Tapping the *already-active* button is a no-op (we skip
     * the recreate to avoid an unnecessary flash).
     */
    private fun wireLanguageToggle(root: View) {
        val group = root.findViewById<MaterialButtonToggleGroup>(R.id.languageToggleGroup)

        // Resolve the currently-active per-app language. Empty list means
        // the user has never explicitly chosen one — in that case we read
        // the actual resolved locale of the fragment context, which is
        // what they're seeing on screen right now.
        val activeTag = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .ifEmpty { resources.configuration.locales[0].toLanguageTag() }
        val isBangla = activeTag.startsWith("bn", ignoreCase = true)
        group.check(if (isBangla) R.id.langBanglaBtn else R.id.langEnglishBtn)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newTag = when (checkedId) {
                R.id.langBanglaBtn -> "bn"
                R.id.langEnglishBtn -> "en"
                else -> return@addOnButtonCheckedListener
            }
            val currentTag = AppCompatDelegate.getApplicationLocales()
                .toLanguageTags()
                .ifEmpty { resources.configuration.locales[0].toLanguageTag() }
            if (currentTag.startsWith(newTag, ignoreCase = true)) return@addOnButtonCheckedListener
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(newTag)
            )
            // setApplicationLocales triggers an automatic Activity recreate
            // on API 33+; on older devices we recreate explicitly so every
            // visible string is re-resolved from the new locale's resources.
            requireActivity().recreate()
        }
    }
}


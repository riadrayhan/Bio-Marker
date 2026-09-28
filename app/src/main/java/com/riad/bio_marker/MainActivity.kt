package com.riad.bio_marker

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.riad.bio_marker.core.ServiceLocator
import com.riad.bio_marker.ui.ConsentActivity
import com.riad.bio_marker.ui.DashboardFragment
import com.riad.bio_marker.ui.SettingsFragment
import com.riad.bio_marker.ui.SummaryFragment
import com.riad.bio_marker.ui.TimelineFragment
import com.riad.bio_marker.ui.ToolsFragment

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!ServiceLocator.consentStore.hasConsent) {
            startActivity(Intent(this, ConsentActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        applyWindowInsets()

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.navToggle)

        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val fragment: Fragment = when (checkedId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_summary   -> SummaryFragment()
                R.id.nav_timeline  -> TimelineFragment()
                R.id.nav_tools     -> ToolsFragment()
                R.id.nav_settings  -> SettingsFragment()
                else -> return@addOnButtonCheckedListener
            }
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.contentContainer, fragment)
                .commit()
        }

        if (savedInstanceState == null) {
            toggle.check(R.id.nav_dashboard)
        }
    }

    /**
     * Edge-to-edge handling:
     *  - Top status-bar inset → padding on the content fragment area.
     *  - Bottom gesture-bar inset → margin on the floating nav card,
     *    so it always sits comfortably above the gesture handle on
     *    every device (3-button nav, gesture nav, foldables, tablets).
     */
    private fun applyWindowInsets() {
        val content = findViewById<android.view.View>(R.id.contentContainer)
        val navCard = findViewById<MaterialCardView>(R.id.navCard)
        val baseNavMargin = navCard.marginBottomDp(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.updatePadding(top = sys.top, left = sys.left, right = sys.right)
            navCard.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = baseNavMargin + sys.bottom
                leftMargin = (16 * resources.displayMetrics.density).toInt() + sys.left
                rightMargin = (16 * resources.displayMetrics.density).toInt() + sys.right
            }
            insets
        }
    }

    private fun android.view.View.marginBottomDp(ctx: android.content.Context): Int {
        val lp = layoutParams as android.view.ViewGroup.MarginLayoutParams
        return lp.bottomMargin
    }
}
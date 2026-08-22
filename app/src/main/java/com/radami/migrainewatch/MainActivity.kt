package com.radami.migrainewatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radami.migrainewatch.ui.navigation.AppNavigation
import com.radami.migrainewatch.ui.navigation.Screen
import com.radami.migrainewatch.ui.theme.MigraineWatchTheme
import com.radami.migrainewatch.workers.PressureFetchWorker
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * A screen the app can be asked to open on top of Today, named by [EXTRA_OPEN_TAB].
     *
     * An enum and not a raw route, so the notification layer names a tab without reaching into
     * the nav graph, and so a name that is not openable can never be handed to it.
     */
    enum class Tab(internal val screen: Screen) {
        PRESSURE(Screen.Pressure)
    }

    companion object {
        /**
         * Which tab to open on top of Today, used by the alert notification to land on the
         * Pressure screen. Holds a [Tab] name rather than a bar position, so reordering the
         * bottom bar cannot silently repoint a pending notification.
         */
        const val EXTRA_OPEN_TAB = "openTab"
    }

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workManager = WorkManager.getInstance(this)
        PressureFetchWorker.schedule(workManager)
        // Periodic work does not run until an interval has elapsed, so refresh and rebuild
        // the pending warnings now as well.
        PressureFetchWorker.runNow(workManager)

        // Only on a fresh launch. The extra is a one-shot instruction and the intent outlives
        // the activity, so an instance rebuilt by a rotation or after process death would
        // otherwise replay it over the tab the user has since navigated to — which the nav
        // controller has just restored.
        val requestedTab = if (savedInstanceState == null) requestedTab() else null

        enableEdgeToEdge()
        setContent {
            val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
            onboardingComplete ?: return@setContent
            val startDestination = if (onboardingComplete == true) Screen.Today.route else Screen.Onboarding.route
            MigraineWatchTheme {
                AppNavigation(
                    startDestination = startDestination,
                    // A tab asked for mid-onboarding would land on a screen with no location
                    // to draw, so it waits until there is an app to open.
                    openTab = requestedTab.takeIf { onboardingComplete == true }
                )
            }
        }
    }

    /**
     * The tab named by [EXTRA_OPEN_TAB], if it names one. This is the launcher activity, so
     * any app on the device can start it with any extras; a name that is not a [Tab] is
     * ignored rather than handed to the nav graph, which would throw on an unknown destination.
     */
    private fun requestedTab(): Screen? {
        val name = intent.getStringExtra(EXTRA_OPEN_TAB) ?: return null
        return Tab.entries.firstOrNull { it.name == name }?.screen
    }
}

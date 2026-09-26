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
     * A screen the app can be asked to open on top of Today, named by [EXTRA_OPEN_TAB]. An
     * enum, not a raw route, so a name that isn't openable can never reach the nav graph.
     */
    enum class Tab(internal val screen: Screen) {
        PRESSURE(Screen.Pressure)
    }

    companion object {
        /**
         * Which tab to open on top of Today. Holds a [Tab] name rather than a bar position, so
         * reordering the bottom bar can't silently repoint a pending notification.
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

        // Only on a fresh launch: the extra is a one-shot instruction, but the intent outlives
        // the activity and would otherwise replay over a tab the nav controller just restored.
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
     * The tab named by [EXTRA_OPEN_TAB], if it names one. Any app can start this launcher
     * activity with arbitrary extras, so an unrecognized name is ignored, not passed to the
     * nav graph.
     */
    private fun requestedTab(): Screen? {
        val name = intent.getStringExtra(EXTRA_OPEN_TAB) ?: return null
        return Tab.entries.firstOrNull { it.name == name }?.screen
    }
}

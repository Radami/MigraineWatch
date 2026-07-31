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

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workManager = WorkManager.getInstance(this)
        PressureFetchWorker.schedule(workManager)
        // Periodic work does not run until an interval has elapsed, so refresh and rebuild
        // the pending warnings now as well.
        PressureFetchWorker.runNow(workManager)
        enableEdgeToEdge()
        setContent {
            val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
            onboardingComplete ?: return@setContent
            val startDestination = if (onboardingComplete == true) Screen.Today.route else Screen.Onboarding.route
            MigraineWatchTheme {
                AppNavigation(startDestination = startDestination)
            }
        }
    }
}

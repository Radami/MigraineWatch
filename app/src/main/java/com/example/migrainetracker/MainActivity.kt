package com.example.migrainetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.migrainetracker.ui.navigation.AppNavigation
import com.example.migrainetracker.ui.navigation.Screen
import com.example.migrainetracker.ui.theme.MigraineWatchTheme
import com.example.migrainetracker.workers.PressureFetchWorker
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PressureFetchWorker.schedule(WorkManager.getInstance(this))
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

package com.example.migrainetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.migrainetracker.ui.screens.alert.AlertDetailScreen
import com.example.migrainetracker.ui.theme.MigraineWatchTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AlertDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MigraineWatchTheme {
                AlertDetailScreen(onBack = { finish() })
            }
        }
    }
}

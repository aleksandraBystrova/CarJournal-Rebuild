package com.carjournal.rebuild

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carjournal.rebuild.ui.JournalApp
import com.carjournal.rebuild.ui.JournalViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use one edge-to-edge policy on all Android versions, including Xiaomi / Android 15+.
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = journalColors()) {
                val vm: JournalViewModel = viewModel()
                // Insets are applied once for every screen, including login and dialogs.
                // safeDrawingPadding stays clear of the status bar, camera cutout and nav bar.
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    JournalApp(vm)
                }
            }
        }
    }
}

@Composable
private fun journalColors() = lightColorScheme(
    primary = Color(0xFF1C67B9),
    secondary = Color(0xFF655A71),
    primaryContainer = Color(0xFFD9ECFF),
    secondaryContainer = Color(0xFFEAE1EE),
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    onSurface = Color(0xFF202027)
)

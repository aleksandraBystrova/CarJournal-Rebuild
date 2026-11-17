package com.carjournal.rebuild

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carjournal.rebuild.ui.JournalApp
import com.carjournal.rebuild.ui.JournalViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = journalColors()) {
                val vm: JournalViewModel = viewModel()
                JournalApp(vm)
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

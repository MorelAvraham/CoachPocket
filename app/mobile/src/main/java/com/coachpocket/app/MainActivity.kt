package com.coachpocket.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.coachpocket.app.ui.CoachScreen
import com.coachpocket.app.ui.EmulatorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The phone stands on a tripod for the whole set; it must not go to sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Two modes, never at once: the coach (camera phone) or the pod stand-in (wrist phone).
                var emulator by rememberSaveable { mutableStateOf(false) }
                if (emulator) {
                    EmulatorScreen(onBack = { emulator = false })
                } else {
                    CoachScreen(onOpenEmulator = { emulator = true })
                }
            }
        }
    }
}

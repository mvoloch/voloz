package io.github.mvoloch.voloz.android.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvoloch.voloz.android.VolozApp
import io.github.mvoloch.voloz.android.data.ThemeMode
import io.github.mvoloch.voloz.android.ui.theme.VolozTheme
import io.github.mvoloch.voloz.android.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VolozApp
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle()
            val dark = isDarkTheme(settings?.theme ?: ThemeMode.LIGHT)
            // Mist background everywhere: system-bar icons follow the app's theme, not the system's.
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            VolozTheme(dark) { VolozRoot(app) }
        }
    }
}

package io.github.mvoloch.voloz.android.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.VolozApp
import io.github.mvoloch.voloz.android.data.ThemeMode
import io.github.mvoloch.voloz.android.dictation.AudioImport
import io.github.mvoloch.voloz.android.ui.components.PrimaryButton
import io.github.mvoloch.voloz.android.ui.components.SecondaryButton
import io.github.mvoloch.voloz.android.ui.components.VolozWordmark
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozTheme
import io.github.mvoloch.voloz.android.ui.theme.VolozType
import io.github.mvoloch.voloz.android.ui.theme.isDarkTheme

/** Receives an audio file (Android share menu, or "Importar áudio" on the home screen) and transcribes it. */
class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VolozApp
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> intent.data
        }
        setContent {
            val dark = isDarkTheme(app.settings.current.theme)
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            VolozTheme(dark) {
                var step by remember { mutableIntStateOf(R.string.import_reading) }
                var result by remember { mutableStateOf<AudioImport.Result?>(null) }
                LaunchedEffect(uri) {
                    result = if (uri == null) {
                        AudioImport.Result.Failed(getString(R.string.import_unreadable))
                    } else {
                        AudioImport(app).run(uri, intent.type) { s -> runOnUiThread { step = s } }
                    }
                }
                ImportScreen(step, result, onCopy = { text -> app.controller.copyText(text) }, onClose = ::finish)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ImportScreen(step: Int, result: AudioImport.Result?, onCopy: (String) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(VolozColors.Mist).safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        VolozWordmark()
        Text(stringResource(R.string.import_title), style = VolozType.PageTitle, color = VolozColors.Ink)
        when (result) {
            null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = VolozColors.Ink, strokeWidth = 2.dp)
                Text(stringResource(step), style = VolozType.Body, color = VolozColors.Graphite)
            }
            is AudioImport.Result.Failed -> {
                Text(result.message, style = VolozType.Body, color = VolozColors.Signal)
                PrimaryButton(stringResource(R.string.import_close), onClose)
            }
            is AudioImport.Result.Done -> {
                Text(stringResource(R.string.import_done), style = VolozType.Caption, color = VolozColors.Graphite)
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).clip(VolozShapes.Card).background(VolozColors.Sheet)
                        .border(1.dp, VolozColors.Hair, VolozShapes.Card).verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    SelectionContainer { Text(result.text, style = VolozType.Body, color = VolozColors.Ink) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.cd_copy), { onCopy(result.text) })
                }
                PrimaryButton(stringResource(R.string.import_close), onClose)
            }
        }
    }
}

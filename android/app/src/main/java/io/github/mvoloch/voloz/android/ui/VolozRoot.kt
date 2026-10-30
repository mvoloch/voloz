package io.github.mvoloch.voloz.android.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.VolozApp
import io.github.mvoloch.voloz.android.data.HistoryEntry
import io.github.mvoloch.voloz.android.net.GroqHttpException
import io.github.mvoloch.voloz.android.service.VolozAccessibilityService
import io.github.mvoloch.voloz.android.ui.screens.AccessibilityScreen
import io.github.mvoloch.voloz.android.ui.screens.FixTarget
import io.github.mvoloch.voloz.android.ui.screens.HistoryRow
import io.github.mvoloch.voloz.android.ui.screens.HomeScreen
import io.github.mvoloch.voloz.android.ui.screens.KeyScreen
import io.github.mvoloch.voloz.android.ui.screens.KeyTestState
import io.github.mvoloch.voloz.android.ui.screens.MicScreen
import io.github.mvoloch.voloz.android.ui.screens.Readiness
import io.github.mvoloch.voloz.android.ui.screens.SettingsScreen
import io.github.mvoloch.voloz.android.ui.screens.WelcomeScreen
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Screens, in onboarding order, then the two main ones. KEY_EDIT = Chave opened from Ajustes. */
enum class Screen { WELCOME, KEY, MIC, ACCESSIBILITY, HOME, SETTINGS, KEY_EDIT }

/**
 * Navigation and wiring only: reads repositories/permissions and hands plain
 * values + callbacks to the stateless screens in ui/screens.
 */
@Composable
fun VolozRoot(app: VolozApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val settingsOrNull by app.settings.settings.collectAsStateWithLifecycle()
    val history by app.history.entries.collectAsStateWithLifecycle()
    val serviceConnected by VolozAccessibilityService.connected.collectAsStateWithLifecycle()

    // Permissions/system state are re-read on every resume (the user returns from Settings).
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val micGranted = remember(resumeTick) { hasMic(context) }
    val a11yOn = serviceConnected || remember(resumeTick) { accessibilityEnabledInSettings(context) }
    val batteryOk = remember(resumeTick) { batteryUnrestricted(context) }

    val settings = settingsOrNull ?: run {
        Box(Modifier.fillMaxSize().background(VolozColors.Paper))
        return
    }

    var screen by rememberSaveable { mutableStateOf(if (settings.groqKey.isBlank()) Screen.WELCOME else Screen.HOME) }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var keyTest by remember { mutableStateOf<KeyTestState>(KeyTestState.Idle) }

    var micDenials by rememberSaveable { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        resumeTick++
        val granted = result[Manifest.permission.RECORD_AUDIO] == true || hasMic(context)
        if (granted && screen == Screen.MIC) screen = Screen.ACCESSIBILITY
        if (!granted) {
            // After two denials Android stops showing the dialog; App info is the only way left.
            micDenials++
            if (micDenials >= 2) openAppInfo(context)
        }
    }
    fun requestMic() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    fun testKey() {
        val key = keyInput.trim()
        keyTest = KeyTestState.Testing
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { app.groq.testKey(key) } }
            keyTest = result.fold(
                onSuccess = {
                    app.settings.setGroqKey(key)
                    KeyTestState.Ok
                },
                onFailure = { KeyTestState.Failed(describeKeyError(context, it)) },
            )
        }
    }

    fun saveKey(next: Screen) {
        val key = keyInput.trim()
        scope.launch {
            app.settings.setGroqKey(key)
            keyInput = ""
            keyTest = KeyTestState.Idle
            screen = next
        }
    }

    BackHandler(enabled = screen != Screen.HOME && screen != Screen.WELCOME) {
        screen = when (screen) {
            Screen.KEY -> Screen.WELCOME
            Screen.MIC -> Screen.KEY
            Screen.ACCESSIBILITY -> Screen.MIC
            Screen.KEY_EDIT -> Screen.SETTINGS
            else -> Screen.HOME
        }
    }

    when (screen) {
        Screen.WELCOME -> WelcomeScreen(onStart = { screen = Screen.KEY })
        Screen.KEY, Screen.KEY_EDIT -> {
            val standalone = screen == Screen.KEY_EDIT
            LaunchedEffect(screen) { if (keyInput.isEmpty() && !standalone) keyInput = settings.groqKey }
            KeyScreen(
                keyInput = keyInput,
                onKeyInputChange = {
                    keyInput = it
                    keyTest = KeyTestState.Idle
                },
                testState = keyTest,
                onTest = ::testKey,
                onContinue = { saveKey(if (standalone) Screen.SETTINGS else Screen.MIC) },
                onBack = { screen = if (standalone) Screen.SETTINGS else Screen.WELCOME },
                standalone = standalone,
            )
        }
        Screen.MIC -> MicScreen(
            micGranted = micGranted,
            onAllow = ::requestMic,
            onContinue = { screen = Screen.ACCESSIBILITY },
            onBack = { screen = Screen.KEY },
        )
        Screen.ACCESSIBILITY -> AccessibilityScreen(
            enabled = a11yOn,
            onOpenSettings = { openAccessibilitySettings(context) },
            onOpenAppInfo = { openAppInfo(context) },
            onDone = { screen = Screen.HOME },
            onBack = { screen = Screen.MIC },
        )
        Screen.HOME -> HomeScreen(
            readiness = Readiness(
                hasKey = settings.groqKey.isNotBlank(),
                micGranted = micGranted,
                accessibilityOn = a11yOn,
                batteryUnrestricted = batteryOk,
            ),
            onlyInTextFields = settings.onlyInTextFields,
            history = history.mapIndexed { i, e -> toRow(context, i, e) },
            onFix = { target ->
                when (target) {
                    FixTarget.KEY -> screen = Screen.KEY_EDIT
                    FixTarget.MIC -> requestMic()
                    FixTarget.ACCESSIBILITY -> screen = Screen.ACCESSIBILITY
                    FixTarget.BATTERY -> requestBatteryExemption(context)
                }
            },
            onCopy = { row ->
                clipboard.setText(AnnotatedString(row.text))
                Toast.makeText(context, R.string.msg_copied, Toast.LENGTH_SHORT).show()
            },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onOnlyInTextFieldsChange = { scope.launch { app.settings.setOnlyInTextFields(it) } },
            onBubbleSizeChange = { scope.launch { app.settings.setBubbleSize(it) } },
            onRefineChange = { scope.launch { app.settings.setRefine(it) } },
            onAddTerm = { term -> scope.launch { app.settings.setVocabulary(settings.vocabulary + term) } },
            onRemoveTerm = { term -> scope.launch { app.settings.setVocabulary(settings.vocabulary - term) } },
            onChangeKey = {
                keyInput = ""
                keyTest = KeyTestState.Idle
                screen = Screen.KEY_EDIT
            },
            onBack = { screen = Screen.HOME },
        )
    }
}

private fun toRow(context: Context, index: Int, e: HistoryEntry): HistoryRow = HistoryRow(
    id = e.timestamp * 100 + index % 100,
    appLabel = e.targetPackage?.let { appLabel(context, it) } ?: context.getString(R.string.home_app_clipboard),
    whenLabel = DateUtils.getRelativeTimeSpanString(e.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    text = e.text,
)

private fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

private fun describeKeyError(context: Context, e: Throwable): String = when {
    e is GroqHttpException && (e.status == 401 || e.status == 403) -> context.getString(R.string.msg_key_invalid)
    e is GroqHttpException && e.status == 429 -> context.getString(R.string.msg_rate_limit)
    e is GroqHttpException -> context.getString(R.string.msg_groq_http, e.status)
    e is IOException -> context.getString(R.string.msg_network)
    else -> context.getString(R.string.msg_unexpected, e.javaClass.simpleName)
}

private fun hasMic(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** The secure setting lists enabled services even before the system binds ours. */
private fun accessibilityEnabledInSettings(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(context, VolozAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

private fun startSafely(context: Context, intent: Intent, fallback: Intent? = null) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        fallback?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

private fun openAccessibilitySettings(context: Context) =
    startSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

private fun openAppInfo(context: Context) = startSafely(
    context,
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
)

private fun requestBatteryExemption(context: Context) = startSafely(
    context,
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
)

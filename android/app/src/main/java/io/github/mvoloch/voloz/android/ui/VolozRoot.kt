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
import android.text.format.DateFormat
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
import io.github.mvoloch.voloz.android.data.KeyMismatchException
import io.github.mvoloch.voloz.android.data.ProviderKind
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.KeyRules
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderException
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.net.hostOf
import io.github.mvoloch.voloz.android.service.VolozAccessibilityService
import io.github.mvoloch.voloz.android.text.Replacement
import io.github.mvoloch.voloz.android.text.Replacements
import io.github.mvoloch.voloz.android.ui.components.Tab
import io.github.mvoloch.voloz.android.ui.screens.AccessibilityScreen
import io.github.mvoloch.voloz.android.ui.screens.FixTarget
import io.github.mvoloch.voloz.android.ui.screens.HistoryActions
import io.github.mvoloch.voloz.android.ui.screens.HistoryRow
import io.github.mvoloch.voloz.android.ui.screens.HomeScreen
import io.github.mvoloch.voloz.android.ui.screens.KeySaveResult
import io.github.mvoloch.voloz.android.ui.screens.KeyScreen
import io.github.mvoloch.voloz.android.ui.screens.KeyTestState
import io.github.mvoloch.voloz.android.ui.screens.MicScreen
import io.github.mvoloch.voloz.android.ui.screens.ProviderActions
import io.github.mvoloch.voloz.android.ui.screens.Readiness
import io.github.mvoloch.voloz.android.ui.screens.SettingsCallbacks
import io.github.mvoloch.voloz.android.ui.screens.SettingsScreen
import io.github.mvoloch.voloz.android.ui.screens.SettingsSection
import io.github.mvoloch.voloz.android.ui.screens.TextRuleActions
import io.github.mvoloch.voloz.android.ui.screens.WelcomeScreen
import io.github.mvoloch.voloz.android.ui.screens.distinctIds
import io.github.mvoloch.voloz.android.ui.screens.modelStatusLine
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Date

/** Screens, in onboarding order, then the two main ones. */
enum class Screen { WELCOME, KEY, MIC, ACCESSIBILITY, HOME, SETTINGS }

/** Onboarding asks for a Groq key: the free way in. Other providers are added in Ajustes. */
private val GROQ_HOST = hostOf(ProviderPresets.GROQ)

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
        Box(Modifier.fillMaxSize().background(VolozColors.Mist))
        return
    }

    var screen by rememberSaveable { mutableStateOf(if (settings.savedKeys.isEmpty()) Screen.WELCOME else Screen.HOME) }
    var section by rememberSaveable { mutableStateOf(SettingsSection.TRANSCRIPTION) }
    // Not rememberSaveable: the key must not end up in the saved instance state.
    var keyInput by remember { mutableStateOf("") }
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
            // Saving runs here too: the Keystore is slow on first use and can throw on some phones.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    KeyRules.mismatch(key, GROQ_HOST)?.let { throw KeyMismatchException(it, GROQ_HOST) }
                    app.api.testKey(ProviderPresets.GROQ, key)
                    app.settings.addKey(GROQ_HOST, key)
                }
            }
            keyTest = result.fold(
                onSuccess = { KeyTestState.Ok },
                onFailure = { KeyTestState.Failed(describeKeyError(context, it)) },
            )
        }
    }

    fun saveKey(next: Screen) {
        val key = keyInput.trim()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { app.settings.addKey(GROQ_HOST, key) } }.onFailure {
                keyTest = KeyTestState.Failed(describeKeyError(context, it))
                return@launch
            }
            keyInput = ""
            keyTest = KeyTestState.Idle
            screen = next
        }
    }

    BackHandler(enabled = screen != Screen.HOME && screen != Screen.WELCOME) {
        screen = when (screen) {
            Screen.KEY -> Screen.WELCOME
            Screen.MIC -> Screen.KEY
            Screen.ACCESSIBILITY -> if (settings.savedKeys.isEmpty()) Screen.MIC else Screen.HOME
            else -> Screen.HOME
        }
    }

    val readiness = Readiness(
        hasKey = settings.hasMainKey,
        micGranted = micGranted,
        accessibilityOn = a11yOn,
        batteryUnrestricted = batteryOk,
    )
    val onTab: (Tab) -> Unit = { screen = if (it == Tab.HOME) Screen.HOME else Screen.SETTINGS }
    val providerActions = remember(app) {
        object : ProviderActions {
            override fun editProviders(kind: ProviderKind, change: (List<Provider>) -> List<Provider>) {
                scope.launch {
                    app.settings.editProviders(kind, change)
                    app.pipeline.resetCooldowns()
                }
            }

            override fun selectKey(host: String, id: String) {
                scope.launch { app.settings.selectKey(host, id) }
            }

            override fun forgetKey(id: String) {
                scope.launch { app.settings.forgetKey(id) }
            }

            override suspend fun saveKey(provider: Provider, key: String, test: Boolean): KeySaveResult {
                val trimmed = key.trim()
                KeyRules.mismatch(trimmed, provider.host)?.let {
                    return KeySaveResult.Failed(context.getString(R.string.msg_key_other_provider, it, provider.host))
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (test) app.api.testKey(provider.endpoint, trimmed)
                        app.settings.addKey(provider.host, trimmed)
                        app.pipeline.resetCooldowns()
                    }
                }
                return result.fold({ KeySaveResult.Ok }, { KeySaveResult.Failed(describeKeyError(context, it)) })
            }
        }
    }

    val historyActions = remember(app) {
        object : HistoryActions {
            override fun copy(text: String) {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, R.string.msg_copied, Toast.LENGTH_SHORT).show()
            }

            override fun saveEdit(row: HistoryRow, text: String, editedRaw: Boolean): List<Replacement> {
                app.history.update(row.timestamp) { it.copy(text = text.trim(), edited = true) }
                // Rules already saved (same "from", any case, same "to") are not suggested again.
                val saved = app.settings.current.replacements.map { it.from.lowercase() to it.to }.toSet()
                return Replacements.suggest(if (editedRaw) row.raw else row.text, text)
                    .filterNot { (it.from.lowercase() to it.to) in saved }
            }

            override fun learn(rule: Replacement) {
                scope.launch { app.settings.addReplacement(rule.from, rule.to) }
            }

            override suspend fun refineAgain(row: HistoryRow): String? {
                val s = app.settings.current
                val route = DictationPipeline.Route(s.sttProviders, s.refineProviders) { s.keyFor(it) }
                val result = withContext(Dispatchers.IO) {
                    runCatching { app.pipeline.refineAgain(route, row.raw, s.vocabulary, DictationPipeline.TextRules(s.replacements, s.snippets)) }
                }.getOrElse { return describeKeyError(context, it) }
                if (result.answer == null && !result.shortcut) return context.getString(R.string.history_refine_failed)
                app.history.update(row.timestamp) {
                    it.copy(
                        text = result.text, refined = result.answer != null, refineModel = result.answer?.provider?.model,
                        refineBackup = result.answer?.backup ?: false, shortcut = result.shortcut, edited = false,
                    )
                }
                return null
            }

            override fun delete(row: HistoryRow) = app.history.remove(row.timestamp)
        }
    }

    // Rebuilt only when the history changes (each row asks PackageManager for the app's name),
    // or on resume, so "Hoje" turns into "Ontem" after midnight.
    val rows = remember(history, resumeTick) {
        val ids = distinctIds(history.map { it.timestamp })
        history.mapIndexed { i, e -> toRow(context, ids[i], e) }
    }

    when (screen) {
        Screen.WELCOME -> WelcomeScreen(onStart = { screen = Screen.KEY })
        Screen.KEY -> {
            LaunchedEffect(screen) { if (keyInput.isEmpty()) keyInput = settings.keyFor(GROQ_HOST)?.key.orEmpty() }
            KeyScreen(
                keyInput = keyInput,
                onKeyInputChange = {
                    keyInput = it
                    keyTest = KeyTestState.Idle
                },
                testState = keyTest,
                onTest = ::testKey,
                onContinue = { saveKey(Screen.MIC) },
                onBack = { screen = Screen.WELCOME },
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
            onBack = { screen = if (settings.savedKeys.isEmpty()) Screen.MIC else Screen.HOME },
        )
        Screen.HOME -> HomeScreen(
            readiness = readiness,
            onlyInTextFields = settings.onlyInTextFields,
            modelStatus = modelStatusLine(settings),
            history = rows,
            onFix = { target ->
                when (target) {
                    FixTarget.KEY -> {
                        section = SettingsSection.TRANSCRIPTION
                        screen = Screen.SETTINGS
                    }
                    FixTarget.MIC -> requestMic()
                    FixTarget.ACCESSIBILITY -> screen = Screen.ACCESSIBILITY
                    FixTarget.BATTERY -> requestBatteryExemption(context)
                }
            },
            onCopy = { row ->
                clipboard.setText(AnnotatedString(row.text))
                Toast.makeText(context, R.string.msg_copied, Toast.LENGTH_SHORT).show()
            },
            historyActions = historyActions,
            onTab = onTab,
        )
        Screen.SETTINGS -> SettingsScreen(
            section = section,
            onSection = { section = it },
            settings = settings,
            readiness = readiness,
            versionName = remember { versionName(context) },
            callbacks = SettingsCallbacks(
                onOnlyInTextFieldsChange = { scope.launch { app.settings.setOnlyInTextFields(it) } },
                onBubbleSizeChange = { scope.launch { app.settings.setBubbleSize(it) } },
                onRefineChange = { scope.launch { app.settings.setRefine(it) } },
                onAddTerm = { term -> scope.launch { app.settings.addTerm(term) } },
                onRemoveTerm = { term -> scope.launch { app.settings.removeTerm(term) } },
                onThemeChange = { scope.launch { app.settings.setTheme(it) } },
                onOpenAccessibility = { openAccessibilitySettings(context) },
                onBattery = { requestBatteryExemption(context) },
                onClearHistory = { app.history.clear() },
                providers = providerActions,
                textRules = TextRuleActions(
                    addReplacement = { from, to -> scope.launch { app.settings.addReplacement(from, to) } },
                    removeReplacement = { from -> scope.launch { app.settings.removeReplacement(from) } },
                    saveSnippet = { trigger, text, replacing -> scope.launch { app.settings.saveSnippet(trigger, text, replacing) } },
                    removeSnippet = { trigger -> scope.launch { app.settings.removeSnippet(trigger) } },
                ),
            ),
            onTab = onTab,
        )
    }
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

private fun toRow(context: Context, id: Long, e: HistoryEntry): HistoryRow {
    val where = e.targetPackage?.let { appLabel(context, it) } ?: context.getString(R.string.home_app_clipboard)
    val refined = if (e.shortcut) {
        context.getString(R.string.home_shortcut)
    } else {
        e.refineModel?.substringAfterLast('/')?.let {
            context.getString(if (e.refineBackup) R.string.home_refined_by_backup else R.string.home_refined_by, it)
        }
    }
    return HistoryRow(
        id = id,
        timestamp = e.timestamp,
        raw = e.raw,
        day = dayLabel(context, e.timestamp),
        time = DateFormat.getTimeFormat(context).format(Date(e.timestamp)),
        durationMs = e.durationMs,
        text = e.text,
        meta = listOfNotNull(where, refined, if (e.edited) context.getString(R.string.home_edited) else null).joinToString(" · "),
    )
}

/** "Hoje", "Ontem", else the short date ("28 de out."). */
private fun dayLabel(context: Context, ts: Long): String = when {
    DateUtils.isToday(ts) -> context.getString(R.string.home_today)
    DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.home_yesterday)
    else -> DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
}

private fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
}

private fun describeKeyError(context: Context, e: Throwable): String = when {
    e is KeyMismatchException -> context.getString(R.string.msg_key_other_provider, e.keyProvider, e.host)
    e is ProviderException && (e.status == 401 || e.status == 403) -> context.getString(R.string.msg_key_invalid)
    e is ProviderException && e.status == 429 -> context.getString(R.string.msg_rate_limit)
    e is ProviderException && e.status != null -> context.getString(R.string.msg_provider_http, e.provider?.displayName.orEmpty(), e.status)
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

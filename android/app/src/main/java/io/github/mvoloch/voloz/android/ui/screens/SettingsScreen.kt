package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.data.BubbleSize
import io.github.mvoloch.voloz.android.data.ProviderKind
import io.github.mvoloch.voloz.android.data.ThemeMode
import io.github.mvoloch.voloz.android.data.VolozSettings
import io.github.mvoloch.voloz.android.net.KeyRules
import io.github.mvoloch.voloz.android.net.Vendor
import io.github.mvoloch.voloz.android.ui.components.BubbleGlyph
import io.github.mvoloch.voloz.android.ui.components.GroupDivider
import io.github.mvoloch.voloz.android.ui.components.PillTone
import io.github.mvoloch.voloz.android.ui.components.SecondaryButton
import io.github.mvoloch.voloz.android.ui.components.SettingRow
import io.github.mvoloch.voloz.android.ui.components.SettingsGroup
import io.github.mvoloch.voloz.android.ui.components.StatusPill
import io.github.mvoloch.voloz.android.ui.components.Tab
import io.github.mvoloch.voloz.android.ui.components.ToggleRow
import io.github.mvoloch.voloz.android.ui.components.TopBar
import io.github.mvoloch.voloz.android.ui.components.VolozIcons
import io.github.mvoloch.voloz.android.ui.components.VolozMark
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.components.dashedOutline
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozDimens
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType

/** The desktop's six settings sections; "Bolha" stands where the desktop has "Atalhos". */
enum class SettingsSection(val title: Int, val lede: Int) {
    BUBBLE(R.string.section_bubble, R.string.section_bubble_lede),
    TRANSCRIPTION(R.string.section_transcription, R.string.section_transcription_lede),
    REFINEMENT(R.string.section_refinement, R.string.section_refinement_lede),
    VOCABULARY(R.string.section_vocabulary, R.string.section_vocabulary_lede),
    GENERAL(R.string.section_general, R.string.section_general_lede),
    ABOUT(R.string.section_about, R.string.section_about_lede),
}

/** Everything the settings can change, wired by VolozRoot. */
class SettingsCallbacks(
    val onOnlyInTextFieldsChange: (Boolean) -> Unit,
    val onBubbleSizeChange: (BubbleSize) -> Unit,
    val onRefineChange: (Boolean) -> Unit,
    val onAddTerm: (String) -> Unit,
    val onRemoveTerm: (String) -> Unit,
    val onThemeChange: (ThemeMode) -> Unit,
    val onOpenAccessibility: () -> Unit,
    val onBattery: () -> Unit,
    val onClearHistory: () -> Unit,
    val providers: ProviderActions,
    val textRules: TextRuleActions,
    val onRefineFlagsChange: (io.github.mvoloch.voloz.android.refine.RefineFlags) -> Unit,
    val onLanguageChange: (String) -> Unit,
    val onAdaptToAppChange: (Boolean) -> Unit,
    val readLog: () -> String,
    val clearLog: () -> Unit,
    val copy: (String) -> Unit,
)

/** The desktop's transcription languages (LiteSettings/pages.tsx LANGUAGES). */
private val LANGUAGES = listOf(
    "auto" to R.string.lang_auto, "pt" to R.string.lang_pt, "en" to R.string.lang_en, "es" to R.string.lang_es,
    "fr" to R.string.lang_fr, "de" to R.string.lang_de, "ja" to R.string.lang_ja, "zh" to R.string.lang_zh, "hi" to R.string.lang_hi,
)

@Composable
fun SettingsScreen(
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    settings: VolozSettings,
    readiness: Readiness,
    versionName: String,
    callbacks: SettingsCallbacks,
    onTab: (Tab) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(VolozColors.Mist).safeDrawingPadding()) {
        TopBar(Tab.SETTINGS, onTab)
        SectionTabs(section, onSection)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = VolozDimens.ScreenPadding, end = VolozDimens.ScreenPadding, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(section.title), style = VolozType.PageTitle, color = VolozColors.Ink)
                Text(stringResource(section.lede), style = VolozType.Body, color = VolozColors.Graphite)
            }
            when (section) {
                SettingsSection.BUBBLE -> BubbleSection(settings, callbacks)
                SettingsSection.TRANSCRIPTION -> {
                    ProviderList(ProviderKind.STT, settings, callbacks.providers)
                    SettingsGroup(title = stringResource(R.string.group_speech)) {
                        SettingRow(stringResource(R.string.settings_language), stringResource(R.string.settings_language_sub)) {
                            Select(LANGUAGES, settings.language.takeIf { code -> LANGUAGES.any { it.first == code } } ?: "auto", callbacks.onLanguageChange)
                        }
                    }
                }
                SettingsSection.REFINEMENT -> {
                    SettingsGroup {
                        ToggleRow(stringResource(R.string.settings_refine), stringResource(R.string.settings_refine_sub), settings.refine, onChange = callbacks.onRefineChange)
                    }
                    ProviderList(ProviderKind.REFINE, settings, callbacks.providers)
                    val f = settings.refineFlags
                    SettingsGroup(title = stringResource(R.string.group_cleanup)) {
                        ToggleRow(stringResource(R.string.refine_cleanup), stringResource(R.string.refine_cleanup_sub), f.smartCleanup, enabled = settings.refine) {
                            callbacks.onRefineFlagsChange(f.copy(smartCleanup = it))
                        }
                        GroupDivider()
                        ToggleRow(stringResource(R.string.refine_self_correction), stringResource(R.string.refine_self_correction_sub), f.selfCorrection, enabled = settings.refine) {
                            callbacks.onRefineFlagsChange(f.copy(selfCorrection = it))
                        }
                        GroupDivider()
                        ToggleRow(stringResource(R.string.refine_adapt), stringResource(R.string.refine_adapt_sub), settings.adaptToApp, enabled = settings.refine) {
                            callbacks.onAdaptToAppChange(it)
                        }
                        GroupDivider()
                        ToggleRow(stringResource(R.string.refine_technical), stringResource(R.string.refine_technical_sub), f.preserveTechnical, enabled = settings.refine) {
                            callbacks.onRefineFlagsChange(f.copy(preserveTechnical = it))
                        }
                    }
                }
                SettingsSection.VOCABULARY -> VocabularySection(settings, callbacks)
                SettingsSection.GENERAL -> GeneralSection(settings, readiness, callbacks)
                SettingsSection.ABOUT -> AboutSection(versionName)
            }
        }
    }
}

/** The desktop's settings side nav as a scrolling row of pills. */
@Composable
private fun SectionTabs(current: SettingsSection, onSection: (SettingsSection) -> Unit) {
    // Six sections don't fit a phone's width: keep the selected one in view.
    val scroll = rememberScrollState()
    val offsets = remember { IntArray(SettingsSection.entries.size) }
    LaunchedEffect(current) { scroll.animateScrollTo((offsets[current.ordinal] - 48).coerceAtLeast(0)) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = VolozDimens.ScreenPadding - 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (s in SettingsSection.entries) {
            val on = s == current
            Box(
                Modifier.onPlaced { offsets[s.ordinal] = it.positionInParent().x.toInt() }
                    .heightIn(min = 36.dp).clip(VolozShapes.Small)
                    .then(if (on) Modifier.background(VolozColors.Sheet).border(1.dp, VolozColors.Hair, VolozShapes.Small) else Modifier)
                    .clickable { onSection(s) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(s.title), style = VolozType.Label.copy(fontWeight = FontWeight.Medium), color = if (on) VolozColors.Ink else VolozColors.Graphite)
            }
        }
    }
}

@Composable
private fun BubbleSection(settings: VolozSettings, cb: SettingsCallbacks) {
    SettingsGroup {
        ToggleRow(stringResource(R.string.settings_only_fields), stringResource(R.string.settings_only_fields_sub), settings.onlyInTextFields, onChange = cb.onOnlyInTextFieldsChange)
        GroupDivider()
        SettingRow(stringResource(R.string.settings_size)) {
            Segmented(
                options = listOf(BubbleSize.SMALL to R.string.size_small, BubbleSize.MEDIUM to R.string.size_medium, BubbleSize.LARGE to R.string.size_large),
                selected = settings.bubbleSize,
                onSelect = cb.onBubbleSizeChange,
            )
        }
        SizePreview(settings.bubbleSize)
    }
    SettingsGroup(title = stringResource(R.string.settings_how)) {
        GestureRow(R.string.gesture_tap, R.string.gesture_tap_body, recording = false)
        GroupDivider()
        GestureRow(R.string.gesture_hold, R.string.gesture_hold_body, recording = true)
        GroupDivider()
        GestureRow(R.string.gesture_double, R.string.gesture_double_body, recording = true)
    }
}

/**
 * The bubble at the size it will have over other apps: Compose dp and the
 * overlay's dp (BubbleView: BubbleSize.dp x density) are the same unit. The box
 * keeps the large size's height so the page doesn't jump when the size changes.
 */
@Composable
private fun SizePreview(size: BubbleSize) {
    val animated by animateDpAsState(size.dp.dp, label = "bubble size")
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .clip(VolozShapes.Field).background(VolozColors.Mist)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.height(BubbleSize.LARGE.dp.dp), contentAlignment = Alignment.Center) {
            BubbleGlyph(size = animated, bars = listOf(18f, 32f, 44f, 28f, 14f).map { it / 96f }, elevated = true)
        }
        Text(stringResource(R.string.settings_size_preview), style = VolozType.Caption, color = VolozColors.Graphite)
    }
}

@Composable
private fun GestureRow(title: Int, body: Int, recording: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BubbleGlyph(size = 36.dp, bars = if (recording) listOf(10f, 18f, 24f, 16f, 9f).map { it / 52f } else null, recDot = recording)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = VolozType.RowTitle, color = VolozColors.Ink)
            Text(stringResource(body), style = VolozType.Caption, color = VolozColors.Graphite)
        }
    }
}

/** Small segmented control (Pequena | Média | Grande). */
@Composable
private fun <T> Segmented(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.clip(VolozShapes.Small).background(VolozColors.Mist).border(1.dp, VolozColors.Hair, VolozShapes.Small).padding(2.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            Box(
                Modifier.heightIn(min = 34.dp).clip(VolozShapes.Small)
                    .then(if (on) Modifier.background(VolozColors.Ink) else Modifier)
                    .clickable { onSelect(value) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = VolozType.Caption.copy(fontWeight = FontWeight.Medium), color = if (on) VolozColors.Mist else VolozColors.Graphite)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VocabularySection(settings: VolozSettings, cb: SettingsCallbacks) {
    var adding by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.settings_vocab_group)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_vocab_sub), style = VolozType.Caption, color = VolozColors.Graphite)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (term in settings.vocabulary) {
                    Row(
                        Modifier.clip(VolozShapes.Pill).background(VolozColors.Mist).border(1.dp, VolozColors.Hair, VolozShapes.Pill)
                            .clickable { cb.onRemoveTerm(term) }.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(term, style = VolozType.BodySmall, color = VolozColors.Ink)
                        Icon(VolozIcons.Close, stringResource(R.string.settings_vocab_remove_cd, term), tint = VolozColors.Graphite, modifier = Modifier.size(13.dp))
                    }
                }
                Box(
                    Modifier.heightIn(min = 34.dp).clip(VolozShapes.Pill).dashedOutline(VolozColors.Faint)
                        .clickable { adding = true }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.settings_vocab_add), style = VolozType.BodySmall.copy(fontWeight = FontWeight.Medium), color = VolozColors.Graphite)
                }
            }
        }
    }
    if (adding) AddTermDialog(onDismiss = { adding = false }, onAdd = { cb.onAddTerm(it); adding = false })
    ReplacementsGroup(settings.replacements, cb.textRules)
    SnippetsGroup(settings.snippets, cb.textRules)
}

@Composable
private fun AddTermDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VolozColors.Sheet,
        title = { Text(stringResource(R.string.settings_vocab_dialog_title), style = VolozType.CardTitle) },
        text = {
            VolozTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.settings_vocab_dialog_hint),
                focusedBorder = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onAdd(text) }) {
                Text(stringResource(R.string.common_add), color = VolozColors.Ink, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = VolozColors.Graphite) } },
    )
}

@Composable
private fun GeneralSection(settings: VolozSettings, readiness: Readiness, cb: SettingsCallbacks) {
    var confirmClear by remember { mutableStateOf(false) }
    SettingsGroup {
        SettingRow(stringResource(R.string.settings_theme)) {
            Select(
                options = listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark),
                selected = settings.theme,
                onSelect = cb.onThemeChange,
            )
        }
        GroupDivider()
        SettingRow(stringResource(R.string.chip_a11y), stringResource(R.string.home_fix_a11y), onClick = cb.onOpenAccessibility) {
            StatusPill(stringResource(if (readiness.accessibilityOn) R.string.status_on else R.string.status_off), if (readiness.accessibilityOn) PillTone.OK else PillTone.WARN)
        }
        GroupDivider()
        SettingRow(
            stringResource(R.string.chip_battery), stringResource(R.string.home_fix_battery),
            onClick = if (readiness.batteryUnrestricted) null else cb.onBattery,
        ) {
            StatusPill(
                stringResource(if (readiness.batteryUnrestricted) R.string.status_ok else R.string.status_recommended),
                if (readiness.batteryUnrestricted) PillTone.OK else PillTone.NEUTRAL,
            )
        }
    }
    SettingsGroup(title = stringResource(R.string.settings_keys_group)) {
        if (settings.savedKeys.isEmpty()) {
            SettingRow(stringResource(R.string.settings_keys_none), stringResource(R.string.settings_keys_none_sub))
        }
        settings.savedKeys.forEachIndexed { i, k ->
            if (i > 0) GroupDivider()
            val inUse = settings.keyFor(k.host)?.id == k.id
            SettingRow(
                title = Vendor.of(k.host).label ?: k.host,
                subtitle = KeyRules.mask(k.key),
                subtitleStyle = VolozType.Mono,
            ) {
                StatusPill(stringResource(if (inUse) R.string.settings_key_in_use else R.string.settings_key_kept), if (inUse) PillTone.OK else PillTone.NEUTRAL)
            }
        }
    }
    Text(stringResource(R.string.settings_keys_hint), style = VolozType.Caption, color = VolozColors.Graphite, modifier = Modifier.padding(horizontal = 4.dp))
    var showLog by remember { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.settings_dictations_group)) {
        SettingRow(stringResource(R.string.settings_history), stringResource(R.string.settings_history_sub)) {
            SecondaryButton(stringResource(R.string.settings_history_clear), { confirmClear = true }, height = 34.dp)
        }
        GroupDivider()
        SettingRow(stringResource(R.string.settings_log), stringResource(R.string.settings_log_sub)) {
            SecondaryButton(stringResource(R.string.settings_log_view), { showLog = true }, height = 34.dp)
        }
    }
    if (showLog) LogDialog(cb, onDismiss = { showLog = false })
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = VolozColors.Sheet,
            title = { Text(stringResource(R.string.settings_history_clear_title), style = VolozType.CardTitle) },
            text = { Text(stringResource(R.string.settings_history_clear_body), style = VolozType.Body) },
            confirmButton = {
                TextButton(onClick = { cb.onClearHistory(); confirmClear = false }) {
                    Text(stringResource(R.string.settings_history_clear), color = VolozColors.Signal, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel), color = VolozColors.Graphite) } },
        )
    }
}

/** The dictation log, newest last, monospace; copy or clear. */
@Composable
private fun LogDialog(cb: SettingsCallbacks, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(cb.readLog()) }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VolozColors.Sheet,
        title = { Text(stringResource(R.string.settings_log), style = VolozType.CardTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(VolozShapes.Small).background(VolozColors.Mist)
                        .verticalScroll(scroll).padding(10.dp),
                ) {
                    Text(text.ifBlank { stringResource(R.string.settings_log_empty) }, style = VolozType.Mono.copy(fontSize = VolozType.Eyebrow.fontSize), color = VolozColors.Ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.cd_copy), { cb.copy(text) }, enabled = text.isNotBlank(), height = 34.dp)
                    SecondaryButton(stringResource(R.string.settings_log_clear), { cb.clearLog(); text = "" }, enabled = text.isNotBlank(), height = 34.dp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done), color = VolozColors.Ink, fontWeight = FontWeight.SemiBold) } },
    )
}

/** Mockup .sel: a mist box with the current value and a chevron, opening a menu. */
@Composable
private fun <T> Select(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(36.dp).clip(VolozShapes.Small).background(VolozColors.Mist).border(1.dp, VolozColors.Hair, VolozShapes.Small)
                .clickable { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(options.first { it.first == selected }.second), style = VolozType.Caption, color = VolozColors.Ink)
            Icon(VolozIcons.Chevron, null, tint = VolozColors.Graphite, modifier = Modifier.size(14.dp).rotate(90f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = VolozColors.Sheet) {
            for ((value, label) in options) {
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

/** Version and the credits that must stay: Voloz is a fork of Voicebox by Jamie Pine. */
@Composable
private fun AboutSection(versionName: String) {
    val uri = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VolozMark(size = 30.dp)
        Text("voloz", style = VolozType.Headline, color = VolozColors.Ink)
        Text("v$versionName", style = VolozType.Mono, color = VolozColors.Graphite)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.about_tagline), style = VolozType.Body, color = VolozColors.Graphite)
        val link = SpanStyle(color = VolozColors.Ink, fontWeight = FontWeight.SemiBold)
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.about_fork_by))
                append(" ")
                withStyle(link) { append("Marcus Voloch") }
            },
            style = VolozType.BodySmall, color = VolozColors.Graphite,
        )
        Text(
            buildAnnotatedString {
                append(stringResource(R.string.about_based_on))
                append(" ")
                withStyle(link) { append("Voicebox") }
                append(" ")
                append(stringResource(R.string.about_by))
                append(" ")
                withStyle(link) { append("Jamie Pine") }
            },
            style = VolozType.BodySmall, color = VolozColors.Graphite,
            modifier = Modifier.clickable { uri.openUri("https://github.com/jamiepine/voicebox") },
        )
    }
    SettingsGroup {
        SettingRow("GitHub", "github.com/mvoloch/voloz", onClick = { uri.openUri("https://github.com/mvoloch/voloz") }, subtitleStyle = VolozType.Mono) {
            Icon(VolozIcons.Chevron, null, tint = VolozColors.Graphite, modifier = Modifier.size(16.dp))
        }
        GroupDivider()
        SettingRow(stringResource(R.string.about_license), stringResource(R.string.about_license_sub))
    }
}

package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.ui.components.BubbleGlyph
import io.github.mvoloch.voloz.android.ui.components.Eyebrow
import io.github.mvoloch.voloz.android.ui.components.GroupDivider
import io.github.mvoloch.voloz.android.ui.components.Hairline
import io.github.mvoloch.voloz.android.ui.components.ModelStatus
import io.github.mvoloch.voloz.android.ui.components.PillTone
import io.github.mvoloch.voloz.android.ui.components.SettingRow
import io.github.mvoloch.voloz.android.ui.components.SettingsGroup
import io.github.mvoloch.voloz.android.ui.components.StatusPill
import io.github.mvoloch.voloz.android.ui.components.Tab
import io.github.mvoloch.voloz.android.ui.components.TopBar
import io.github.mvoloch.voloz.android.ui.components.VolozIcons
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozDimens
import io.github.mvoloch.voloz.android.ui.theme.VolozType
import java.text.NumberFormat
import kotlin.math.sqrt

/** What the setup card checks. Battery is advisory, not part of "ready". */
data class Readiness(
    val hasKey: Boolean,
    val micGranted: Boolean,
    val accessibilityOn: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val ready: Boolean get() = hasKey && micGranted && accessibilityOn
}

enum class FixTarget { KEY, MIC, ACCESSIBILITY, BATTERY }

/** One history row, already formatted for display. */
data class HistoryRow(
    val id: Long,
    val day: String,
    val time: String,
    val durationMs: Long,
    val text: String,
    /** "WhatsApp · refinado por qwen3.8-27b" */
    val meta: String,
)

/** Bar length for a dictation: square root so a 5 s note and a 2 min memo both read (desktop speechBarWidth). */
fun speechBarFraction(durationMs: Long): Float {
    val seconds = maxOf(0.0, durationMs / 1000.0)
    return (sqrt(seconds / 120.0)).coerceIn(0.06, 1.0).toFloat()
}

fun formatDuration(ms: Long): String {
    val s = (ms + 500) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Words of all rows and minutes of speech, as the desktop's home totals. */
fun totals(rows: List<HistoryRow>): Pair<Int, Int> {
    val words = rows.sumOf { r -> r.text.split(Regex("\\s+")).count { it.isNotEmpty() } }
    val minutes = Math.round(rows.sumOf { it.durationMs } / 60_000.0).toInt()
    return words to minutes
}

@Composable
fun HomeScreen(
    readiness: Readiness,
    onlyInTextFields: Boolean,
    modelStatus: String,
    history: List<HistoryRow>,
    onFix: (FixTarget) -> Unit,
    onCopy: (HistoryRow) -> Unit,
    onTab: (Tab) -> Unit,
) {
    var tryText by rememberSaveable { mutableStateOf("") }
    var tryFocused by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val shown = if (query.isBlank()) history else history.filter { it.text.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().background(VolozColors.Mist).safeDrawingPadding()) {
        TopBar(Tab.HOME, onTab)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = VolozDimens.ScreenPadding, end = VolozDimens.ScreenPadding, top = 28.dp, bottom = 32.dp),
        ) {
            item { Hero(onlyInTextFields) }
            item {
                ModelStatus(modelStatus, ok = readiness.ready, modifier = Modifier.padding(top = 16.dp))
            }
            if (!readiness.ready || !readiness.batteryUnrestricted) {
                item { SetupCard(readiness, onFix, Modifier.padding(top = 22.dp)) }
            }
            item {
                VolozTextField(
                    value = tryText,
                    onValueChange = { tryText = it },
                    placeholder = stringResource(R.string.home_try_placeholder),
                    focusedBorder = tryFocused,
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp).onFocusChanged { tryFocused = it.isFocused },
                )
            }
            if (history.isNotEmpty()) {
                item {
                    val (words, minutes) = totals(history)
                    val nf = NumberFormat.getIntegerInstance()
                    Text(
                        buildAnnotatedString {
                            val strong = SpanStyle(color = VolozColors.Ink, fontWeight = FontWeight.SemiBold)
                            withStyle(strong) { append(pluralStringResource(R.plurals.home_words, words, nf.format(words))) }
                            append(" ")
                            append(stringResource(R.string.home_totals_in))
                            append(" ")
                            withStyle(strong) { append(stringResource(R.string.home_minutes, nf.format(minutes))) }
                            append(" ")
                            append(stringResource(R.string.home_totals_speech))
                            append(" · ")
                            append(pluralStringResource(R.plurals.home_count, history.size, history.size))
                        },
                        style = VolozType.Caption, color = VolozColors.Graphite,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                item {
                    VolozTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.home_search),
                        focusedBorder = searchFocused,
                        leading = { Icon(VolozIcons.Search, null, tint = VolozColors.Faint, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 26.dp).onFocusChanged { searchFocused = it.isFocused },
                    )
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (history.isEmpty()) stringResource(R.string.home_empty) else stringResource(R.string.home_no_matches, query.trim()),
                        style = VolozType.BodySmall, color = VolozColors.Graphite, modifier = Modifier.padding(top = 26.dp),
                    )
                }
            }
            var lastDay: String? = null
            for (row in shown) {
                if (row.day != lastDay) {
                    val day = row.day
                    item(key = "day-$day") { DayHeader(day) }
                    lastDay = day
                }
                item(key = row.id) {
                    HistoryItem(row, open = openId == row.id, onToggle = { openId = if (openId == row.id) null else row.id }, onCopy = onCopy)
                }
            }
        }
    }
}

/** "Toque na [bolha] e fale." with the bubble drawn inline like the desktop's keycaps. */
@Composable
private fun Hero(onlyInTextFields: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val text = buildAnnotatedString {
            append(stringResource(R.string.home_hero_before))
            append(" ")
            appendInlineContent("bubble", "[bolha]")
            append(" ")
            append(stringResource(R.string.home_hero_after))
        }
        val inline = mapOf(
            "bubble" to InlineTextContent(Placeholder(1.25.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BubbleGlyph(size = 38.dp, bars = listOf(10f, 18f, 24f, 16f, 9f).map { it / 52f })
                }
            },
        )
        Text(text, inlineContent = inline, style = VolozType.Hero, color = VolozColors.Ink)
        Text(
            stringResource(if (onlyInTextFields) R.string.home_hero_sub else R.string.home_hero_sub_always),
            style = VolozType.Body, color = VolozColors.Graphite,
        )
    }
}

@Composable
private fun SetupCard(r: Readiness, onFix: (FixTarget) -> Unit, modifier: Modifier = Modifier) {
    SettingsGroup(stringResource(if (r.ready) R.string.home_setup_recommended else R.string.home_setup_missing), modifier) {
        val ok = stringResource(R.string.status_ok)
        val missing = stringResource(R.string.status_missing)
        if (!r.ready) {
            SetupRow(R.string.chip_key, R.string.home_fix_key, r.hasKey, ok, missing) { onFix(FixTarget.KEY) }
            GroupDivider()
            SetupRow(R.string.chip_mic, R.string.home_fix_mic, r.micGranted, ok, missing) { onFix(FixTarget.MIC) }
            GroupDivider()
            SetupRow(R.string.chip_a11y, R.string.home_fix_a11y, r.accessibilityOn, ok, missing) { onFix(FixTarget.ACCESSIBILITY) }
            GroupDivider()
        }
        SettingRow(
            title = stringResource(R.string.chip_battery),
            subtitle = stringResource(R.string.home_fix_battery),
            onClick = if (r.batteryUnrestricted) null else ({ onFix(FixTarget.BATTERY) }),
        ) {
            StatusPill(if (r.batteryUnrestricted) ok else stringResource(R.string.status_recommended), if (r.batteryUnrestricted) PillTone.OK else PillTone.NEUTRAL)
        }
    }
}

@Composable
private fun SetupRow(title: Int, subtitle: Int, done: Boolean, ok: String, missing: String, onFix: () -> Unit) {
    SettingRow(stringResource(title), stringResource(subtitle), onClick = if (done) null else onFix) {
        StatusPill(if (done) ok else missing, if (done) PillTone.OK else PillTone.WARN)
    }
}

/** Mono day label with a hairline to the right (mockup .day). */
@Composable
private fun DayHeader(day: String) {
    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(day)
        Spacer(Modifier.width(12.dp))
        Hairline(Modifier.weight(1f))
    }
}

/** Time, a bar as long as the speech, the text (two lines, all of it when open) and where it went. */
@Composable
private fun HistoryItem(row: HistoryRow, open: Boolean, onToggle: () -> Unit, onCopy: (HistoryRow) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(60.dp).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.time, style = VolozType.Mono, color = VolozColors.Graphite)
                if (row.durationMs > 0) {
                    Box(
                        Modifier.fillMaxWidth(speechBarFraction(row.durationMs)).height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(VolozColors.Ink.copy(alpha = 0.82f)),
                    )
                    Text(formatDuration(row.durationMs), style = VolozType.Mono.copy(fontSize = VolozType.Eyebrow.fontSize), color = VolozColors.Faint)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    row.text, style = VolozType.BodySmall, color = VolozColors.Ink,
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
                Text(row.meta, style = VolozType.Meta, color = VolozColors.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onCopy(row) }, modifier = Modifier.size(36.dp)) {
                Icon(VolozIcons.Copy, stringResource(R.string.cd_copy), tint = VolozColors.Graphite, modifier = Modifier.size(18.dp))
            }
        }
        Hairline()
    }
}

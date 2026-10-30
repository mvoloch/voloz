package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.ui.components.BubbleGlyph
import io.github.mvoloch.voloz.android.ui.components.Pill
import io.github.mvoloch.voloz.android.ui.components.VolozIcons
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType

/** What the status card checks. Battery is advisory, not part of "ready". */
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
data class HistoryRow(val id: Long, val appLabel: String, val whenLabel: String, val text: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    readiness: Readiness,
    onlyInTextFields: Boolean,
    history: List<HistoryRow>,
    onFix: (FixTarget) -> Unit,
    onCopy: (HistoryRow) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var tryText by rememberSaveable { mutableStateOf("") }
    var tryFocused by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(VolozColors.Paper).safeDrawingPadding()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name), style = VolozType.Wordmark, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(44.dp)) {
                        Icon(VolozIcons.Gear, stringResource(R.string.cd_settings), tint = VolozColors.Ink, modifier = Modifier.size(22.dp))
                    }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(VolozShapes.CardLarge).background(VolozColors.Ink)
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BubbleGlyph(size = 52.dp, body = VolozColors.InkRaised)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                stringResource(if (readiness.ready) R.string.home_ready_title else R.string.home_missing_title),
                                style = VolozType.CardTitle, color = VolozColors.Paper,
                            )
                            Text(
                                stringResource(
                                    when {
                                        !readiness.ready -> R.string.home_missing_sub
                                        onlyInTextFields -> R.string.home_ready_sub
                                        else -> R.string.home_ready_sub_always
                                    },
                                ),
                                fontSize = 14.sp, color = VolozColors.OnInkSecondary,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(R.string.chip_key, readiness.hasKey) { onFix(FixTarget.KEY) }
                        StatusChip(R.string.chip_mic, readiness.micGranted) { onFix(FixTarget.MIC) }
                        StatusChip(R.string.chip_a11y, readiness.accessibilityOn) { onFix(FixTarget.ACCESSIBILITY) }
                        StatusChip(R.string.chip_battery, readiness.batteryUnrestricted) { onFix(FixTarget.BATTERY) }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_try_label), style = VolozType.Label)
                    VolozTextField(
                        value = tryText,
                        onValueChange = { tryText = it },
                        placeholder = stringResource(R.string.home_try_placeholder),
                        focusedBorder = tryFocused,
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { tryFocused = it.isFocused },
                    )
                }
            }
            item { Text(stringResource(R.string.home_recent), style = VolozType.Label) }
            if (history.isEmpty()) {
                item {
                    Text(stringResource(R.string.home_empty), fontSize = 14.sp, color = VolozColors.TextTertiary)
                }
            }
            items(history, key = { it.id }) { row -> HistoryItem(row, onCopy) }
        }
    }
}

@Composable
private fun StatusChip(label: Int, ok: Boolean, onFix: () -> Unit) {
    Pill(
        text = stringResource(label),
        background = VolozColors.InkRaised,
        color = VolozColors.OnInkChip,
        dot = if (ok) VolozColors.Lime else VolozColors.Amber,
        onClick = if (ok) null else onFix,
    )
}

@Composable
private fun HistoryItem(row: HistoryRow, onCopy: (HistoryRow) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(VolozShapes.ListItem).background(VolozColors.Card)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${row.appLabel} · ${row.whenLabel}", fontSize = 12.5.sp, color = VolozColors.TextTertiary)
            Text(row.text, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onCopy(row) }, modifier = Modifier.size(44.dp)) {
            Icon(VolozIcons.Copy, stringResource(R.string.cd_copy), tint = VolozColors.TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

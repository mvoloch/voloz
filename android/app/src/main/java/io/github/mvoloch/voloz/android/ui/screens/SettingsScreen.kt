package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.data.BubbleSize
import io.github.mvoloch.voloz.android.data.VolozSettings
import io.github.mvoloch.voloz.android.ui.components.BackButton
import io.github.mvoloch.voloz.android.ui.components.GroupDivider
import io.github.mvoloch.voloz.android.ui.components.SettingsGroup
import io.github.mvoloch.voloz.android.ui.components.VolozIcons
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: VolozSettings,
    onOnlyInTextFieldsChange: (Boolean) -> Unit,
    onBubbleSizeChange: (BubbleSize) -> Unit,
    onRefineChange: (Boolean) -> Unit,
    onAddTerm: (String) -> Unit,
    onRemoveTerm: (String) -> Unit,
    onChangeKey: () -> Unit,
    onBack: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(VolozColors.Paper).safeDrawingPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackButton(onBack)
                Text(stringResource(R.string.settings_title), style = VolozType.Wordmark)
            }

            SettingsGroup(stringResource(R.string.settings_group_bubble)) {
                SwitchRow(
                    title = stringResource(R.string.settings_only_fields),
                    subtitle = stringResource(R.string.settings_only_fields_sub),
                    checked = settings.onlyInTextFields,
                    onChange = onOnlyInTextFieldsChange,
                )
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.settings_size), style = RowTitle)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SizeOption(R.string.size_small, settings.bubbleSize == BubbleSize.SMALL, Modifier.weight(1f)) { onBubbleSizeChange(BubbleSize.SMALL) }
                        SizeOption(R.string.size_medium, settings.bubbleSize == BubbleSize.MEDIUM, Modifier.weight(1f)) { onBubbleSizeChange(BubbleSize.MEDIUM) }
                        SizeOption(R.string.size_large, settings.bubbleSize == BubbleSize.LARGE, Modifier.weight(1f)) { onBubbleSizeChange(BubbleSize.LARGE) }
                    }
                }
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_how), style = RowTitle)
                    Text(stringResource(R.string.settings_how_body), style = VolozType.Caption, color = VolozColors.TextTertiary)
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_text)) {
                SwitchRow(
                    title = stringResource(R.string.settings_refine),
                    subtitle = stringResource(R.string.settings_refine_sub),
                    checked = settings.refine,
                    onChange = onRefineChange,
                )
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_vocab), style = RowTitle)
                        Text(stringResource(R.string.settings_vocab_sub), style = RowSubtitle, color = VolozColors.TextTertiary)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (term in settings.vocabulary) VocabChip(term) { onRemoveTerm(term) }
                        AddChip { adding = true }
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_account)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_key), style = RowTitle)
                        Text(
                            maskKey(settings.groqKey) ?: stringResource(R.string.settings_key_none),
                            style = VolozType.MonoKey.copy(fontSize = 13.sp), color = VolozColors.TextTertiary,
                        )
                    }
                    OutlinedButton(
                        onClick = onChangeKey,
                        shape = VolozShapes.Small,
                        border = BorderStroke(1.5.dp, VolozColors.FieldBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VolozColors.Ink, containerColor = VolozColors.Card),
                        modifier = Modifier.height(40.dp),
                    ) { Text(stringResource(R.string.settings_key_change), fontSize = 14.sp, fontWeight = FontWeight.Normal) }
                }
            }
        }
    }

    if (adding) AddTermDialog(onDismiss = { adding = false }, onAdd = { onAddTerm(it); adding = false })
}

private val RowTitle = TextStyle(fontSize = 16.sp, lineHeight = 20.sp)
private val RowSubtitle = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)

/** First 8 characters, "•••••", last 3: never the whole key on screen. */
fun maskKey(key: String): String? {
    if (key.isBlank()) return null
    if (key.length <= 12) return "••••• " + key.takeLast(2)
    return key.take(8) + " ••••• " + key.takeLast(3)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = RowTitle)
            Text(subtitle, style = RowSubtitle, color = VolozColors.TextTertiary)
        }
        // Mockup: a 22px olive checkbox. The whole row is the touch target, so the
        // box itself skips the 48dp minimum that would make the row taller.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.size(24.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = VolozColors.Olive,
                    checkmarkColor = Color.White,
                    uncheckedColor = VolozColors.TextTertiary,
                ),
            )
        }
    }
}

@Composable
private fun SizeOption(label: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(VolozShapes.Small)
            .background(if (selected) VolozColors.Ink else VolozColors.Card)
            .border(1.5.dp, if (selected) VolozColors.Ink else VolozColors.FieldBorder, VolozShapes.Small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(label), fontSize = 15.sp, color = if (selected) VolozColors.Paper else VolozColors.Ink)
    }
}

@Composable
private fun VocabChip(term: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(VolozShapes.Pill).background(VolozColors.OliveTint).clickable(onClick = onRemove)
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(term, fontSize = 14.sp, color = VolozColors.OliveTintText)
        Icon(VolozIcons.Close, stringResource(R.string.settings_vocab_remove_cd, term), tint = VolozColors.OliveTintText, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun AddChip(onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 32.dp).clip(VolozShapes.Pill)
            .drawBehind {
                drawRoundRect(
                    color = VolozColors.OliveDashed,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                    cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.settings_vocab_add), fontSize = 14.sp, color = VolozColors.OliveDark)
    }
}

@Composable
private fun AddTermDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VolozColors.Card,
        title = { Text(stringResource(R.string.settings_vocab_dialog_title)) },
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
            TextButton(onClick = { if (text.isNotBlank()) onAdd(text) }, colors = ButtonDefaults.textButtonColors(contentColor = VolozColors.Olive)) {
                Text(stringResource(R.string.common_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = VolozColors.TextSecondary)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

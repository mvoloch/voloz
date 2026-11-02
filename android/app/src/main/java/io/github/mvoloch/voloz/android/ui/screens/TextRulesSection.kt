package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.text.Replacement
import io.github.mvoloch.voloz.android.text.Replacements
import io.github.mvoloch.voloz.android.text.Snippet
import io.github.mvoloch.voloz.android.text.Snippets
import io.github.mvoloch.voloz.android.ui.components.AddRow
import io.github.mvoloch.voloz.android.ui.components.Eyebrow
import io.github.mvoloch.voloz.android.ui.components.GroupDivider
import io.github.mvoloch.voloz.android.ui.components.SettingsGroup
import io.github.mvoloch.voloz.android.ui.components.VolozIcons
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozType

/** What the replacement and shortcut lists can change; wired by VolozRoot. */
class TextRuleActions(
    val addReplacement: (from: String, to: String) -> Unit,
    val removeReplacement: (from: String) -> Unit,
    /** [replacing] = the trigger being edited, so a renamed shortcut doesn't leave its old one behind. */
    val saveSnippet: (trigger: String, text: String, replacing: String?) -> Unit,
    val removeSnippet: (trigger: String) -> Unit,
)

/** Desktop "Substituições": a word or phrase the transcription always hears wrong. */
@Composable
fun ReplacementsGroup(rules: List<Replacement>, actions: TextRuleActions) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup(title = stringResource(R.string.replacements_title)) {
            Text(
                stringResource(R.string.replacements_sub), style = VolozType.Caption, color = VolozColors.Graphite,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            )
            if (rules.isEmpty()) {
                Text(
                    stringResource(R.string.replacements_empty), style = VolozType.Caption, color = VolozColors.Faint,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            rules.forEachIndexed { i, r ->
                if (i > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.from, style = VolozType.BodySmall, color = VolozColors.Graphite, modifier = Modifier.weight(1f, fill = false))
                    Text("  →  ", style = VolozType.BodySmall, color = VolozColors.Faint)
                    Text(r.to, style = VolozType.RowTitle, color = VolozColors.Ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.removeReplacement(r.from) }) {
                        Icon(VolozIcons.Close, stringResource(R.string.replacements_remove_cd, r.from, r.to), tint = VolozColors.Graphite, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (rules.size < Replacements.MAX_RULES) AddRow(stringResource(R.string.replacements_add)) { adding = true }
        }
    }
    if (adding) {
        TwoFieldDialog(
            title = stringResource(R.string.replacements_add),
            firstLabel = stringResource(R.string.replacements_from),
            firstHint = stringResource(R.string.replacements_from_hint),
            secondLabel = stringResource(R.string.replacements_to),
            secondHint = stringResource(R.string.replacements_to_hint),
            multilineSecond = false,
            maxFirst = Replacements.MAX_SIDE_CHARS,
            maxSecond = Replacements.MAX_SIDE_CHARS,
            onDismiss = { adding = false },
            onSave = { from, to -> actions.addReplacement(from, to); adding = false },
        )
    }
}

/** Desktop "Atalhos de texto": say a short phrase, get a saved text. */
@Composable
fun SnippetsGroup(snippets: List<Snippet>, actions: TextRuleActions) {
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.snippets_title)) {
        Text(
            stringResource(R.string.snippets_sub), style = VolozType.Caption, color = VolozColors.Graphite,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        )
        if (snippets.isEmpty()) {
            Text(
                stringResource(R.string.snippets_empty), style = VolozType.Caption, color = VolozColors.Faint,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        snippets.forEachIndexed { i, s ->
            if (i > 0) GroupDivider()
            Column(
                Modifier.fillMaxWidth().clickable { editing = s }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("“${s.trigger}”", style = VolozType.RowTitle, color = VolozColors.Ink)
                Text(s.text, style = VolozType.Caption, color = VolozColors.Graphite, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (snippets.size < Snippets.MAX_SNIPPETS) AddRow(stringResource(R.string.snippets_add)) { adding = true }
    }
    val current = editing
    if (adding || current != null) {
        TwoFieldDialog(
            title = if (current == null) stringResource(R.string.snippets_add) else stringResource(R.string.snippets_edit, current.trigger),
            firstLabel = stringResource(R.string.snippets_trigger),
            firstHint = stringResource(R.string.snippets_trigger_hint),
            secondLabel = stringResource(R.string.snippets_text),
            secondHint = stringResource(R.string.snippets_text_hint),
            multilineSecond = true,
            maxFirst = Snippets.MAX_TRIGGER_CHARS,
            maxSecond = Snippets.MAX_TEXT_CHARS,
            initialFirst = current?.trigger.orEmpty(),
            initialSecond = current?.text.orEmpty(),
            note = stringResource(R.string.snippets_replaces),
            onDismiss = { adding = false; editing = null },
            onSave = { trigger, text -> actions.saveSnippet(trigger, text, current?.trigger); adding = false; editing = null },
            onRemove = current?.let { s -> { actions.removeSnippet(s.trigger); editing = null } },
        )
    }
}

@Composable
private fun TwoFieldDialog(
    title: String,
    firstLabel: String,
    firstHint: String,
    secondLabel: String,
    secondHint: String,
    multilineSecond: Boolean,
    maxFirst: Int,
    maxSecond: Int,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    initialFirst: String = "",
    initialSecond: String = "",
    note: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    var first by rememberSaveable { mutableStateOf(initialFirst) }
    var second by rememberSaveable { mutableStateOf(initialSecond) }
    val ok = first.isNotBlank() && second.isNotBlank() && first.trim().length <= maxFirst && second.trim().length <= maxSecond
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VolozColors.Sheet,
        title = { Text(title, style = VolozType.CardTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(firstLabel)
                VolozTextField(first, { first = it }, placeholder = firstHint, modifier = Modifier.fillMaxWidth())
                Eyebrow(secondLabel, Modifier.padding(top = 4.dp))
                VolozTextField(second, { second = it }, placeholder = secondHint, singleLine = !multilineSecond, modifier = Modifier.fillMaxWidth())
                if (note != null) Text(note, style = VolozType.Caption, color = VolozColors.Graphite)
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.providers_remove), color = VolozColors.Signal) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(first, second) }, enabled = ok) {
                Text(stringResource(R.string.key_save), color = if (ok) VolozColors.Ink else VolozColors.Faint, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = VolozColors.Graphite) } },
    )
}

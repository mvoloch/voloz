package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.text.Replacement
import io.github.mvoloch.voloz.android.ui.components.Eyebrow
import io.github.mvoloch.voloz.android.ui.components.SecondaryButton
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType
import kotlinx.coroutines.launch

/** What a history entry can do; wired by VolozRoot. */
interface HistoryActions {
    fun copy(text: String)
    /** Stores the hand correction and returns the replacement rules it suggests (desktop "learn from corrections"). */
    fun saveEdit(row: HistoryRow, text: String, editedRaw: Boolean): List<Replacement>
    fun learn(rule: Replacement)
    /** Runs the stored raw transcript through refinement again; null when it worked, else what went wrong. */
    suspend fun refineAgain(row: HistoryRow): String?
    fun delete(row: HistoryRow)
}

/**
 * One dictation, as the desktop's capture details: the text and the original
 * transcript, copy, correct by hand (and learn replacement rules from it), refine
 * again, delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryDetail(row: HistoryRow, actions: HistoryActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showRaw by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val suggestions = remember { mutableStateListOf<Replacement>() }
    val hasRaw = row.raw.isNotBlank() && row.raw != row.text
    val shown = if (showRaw && hasRaw) row.raw else row.text
    val refineDone = stringResource(R.string.history_refined_again)
    val savedMessage = stringResource(R.string.history_saved)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VolozColors.Sheet,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${row.day} · ${row.time}", style = VolozType.CardTitle)
                Text(row.meta, style = VolozType.Caption, color = VolozColors.Graphite)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasRaw && !editing) {
                    Row(
                        Modifier.clip(VolozShapes.Small).background(VolozColors.Mist).border(1.dp, VolozColors.Hair, VolozShapes.Small).padding(2.dp),
                    ) {
                        for ((raw, label) in listOf(false to R.string.history_text, true to R.string.history_original)) {
                            val on = showRaw == raw
                            Box(
                                Modifier.heightIn(min = 32.dp).clip(VolozShapes.Small)
                                    .then(if (on) Modifier.background(VolozColors.Ink) else Modifier)
                                    .clickable { showRaw = raw }.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(label), style = VolozType.Caption.copy(fontWeight = FontWeight.Medium), color = if (on) VolozColors.Mist else VolozColors.Graphite)
                            }
                        }
                    }
                }
                if (editing) {
                    VolozTextField(draft, { draft = it }, singleLine = false, focusedBorder = true, modifier = Modifier.fillMaxWidth())
                } else {
                    SelectionContainer { Text(shown, style = VolozType.Body, color = VolozColors.Ink) }
                }
                message?.let { Text(it, style = VolozType.Caption, color = VolozColors.Graphite) }
                if (suggestions.isNotEmpty()) {
                    Eyebrow(stringResource(R.string.history_learn))
                    for (rule in suggestions.toList()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${rule.from}  →  ${rule.to}", style = VolozType.BodySmall, color = VolozColors.Ink, modifier = Modifier.weight(1f))
                            TextButton(onClick = { actions.learn(rule); suggestions.remove(rule) }) {
                                Text(stringResource(R.string.common_add), color = VolozColors.Ink, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing) {
                        SecondaryButton(stringResource(R.string.key_save), {
                            suggestions.clear()
                            suggestions += actions.saveEdit(row, draft, editedRaw = showRaw && hasRaw)
                            editing = false
                            showRaw = false
                            message = savedMessage
                        }, enabled = draft.isNotBlank(), height = 36.dp)
                        SecondaryButton(stringResource(R.string.common_cancel), { editing = false }, height = 36.dp)
                    } else {
                        SecondaryButton(stringResource(R.string.cd_copy), { actions.copy(shown) }, height = 36.dp)
                        SecondaryButton(stringResource(R.string.history_edit), { draft = shown; editing = true; message = null }, height = 36.dp)
                        if (row.raw.isNotBlank()) {
                            SecondaryButton(
                                stringResource(if (busy) R.string.history_refining else R.string.history_refine_again),
                                {
                                    busy = true
                                    message = null
                                    scope.launch {
                                        message = actions.refineAgain(row) ?: refineDone
                                        busy = false
                                    }
                                },
                                enabled = !busy, height = 36.dp,
                            )
                        }
                        SecondaryButton(stringResource(R.string.history_delete), { confirmDelete = true }, height = 36.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done), color = VolozColors.Ink, fontWeight = FontWeight.SemiBold) } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = VolozColors.Sheet,
            title = { Text(stringResource(R.string.history_delete_title), style = VolozType.CardTitle) },
            text = { Text(stringResource(R.string.history_delete_body), style = VolozType.Body) },
            confirmButton = {
                TextButton(onClick = { actions.delete(row); confirmDelete = false; onDismiss() }) {
                    Text(stringResource(R.string.history_delete), color = VolozColors.Signal, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel), color = VolozColors.Graphite) } },
        )
    }
}

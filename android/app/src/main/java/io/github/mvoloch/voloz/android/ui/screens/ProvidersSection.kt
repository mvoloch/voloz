package io.github.mvoloch.voloz.android.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.data.ProviderKind
import io.github.mvoloch.voloz.android.data.SavedKey
import io.github.mvoloch.voloz.android.data.VolozSettings
import io.github.mvoloch.voloz.android.net.KeyRules
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.ui.components.AddRow
import io.github.mvoloch.voloz.android.ui.components.Eyebrow
import io.github.mvoloch.voloz.android.ui.components.GroupDivider
import io.github.mvoloch.voloz.android.ui.components.PillTone
import io.github.mvoloch.voloz.android.ui.components.RankBadge
import io.github.mvoloch.voloz.android.ui.components.SecondaryButton
import io.github.mvoloch.voloz.android.ui.components.SettingsGroup
import io.github.mvoloch.voloz.android.ui.components.SquareControl
import io.github.mvoloch.voloz.android.ui.components.StatusPill
import io.github.mvoloch.voloz.android.ui.components.VolozIcons
import io.github.mvoloch.voloz.android.ui.components.VolozTextField
import io.github.mvoloch.voloz.android.ui.theme.VolozColors
import io.github.mvoloch.voloz.android.ui.theme.VolozShapes
import io.github.mvoloch.voloz.android.ui.theme.VolozType
import kotlinx.coroutines.launch

/** Result of saving (and optionally testing) a key. */
sealed interface KeySaveResult {
    data object Ok : KeySaveResult
    data class Failed(val message: String) : KeySaveResult
}

/** What the providers list can change; implemented by VolozRoot over the repositories. */
interface ProviderActions {
    /** Changes the stored list (see SettingsRepository.editProviders). */
    fun editProviders(kind: ProviderKind, change: (List<Provider>) -> List<Provider>)
    fun selectKey(host: String, id: String)
    fun forgetKey(id: String)
    /** Keeps [key] for [provider]'s host after an optional test against its `/models`. */
    suspend fun saveKey(provider: Provider, key: String, test: Boolean): KeySaveResult
}

/**
 * Main + backups as one ordered list (desktop ProviderList): rank, provider and
 * model, key status, move up / more. Moving a provider to the top makes it the
 * main one. Keys are kept per host and picked in the key dialog.
 */
@Composable
fun ProviderList(kind: ProviderKind, settings: VolozSettings, actions: ProviderActions) {
    val list = settings.providers(kind)
    var keyFor by remember { mutableStateOf<Provider?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup(title = stringResource(R.string.providers_group)) {
            list.forEachIndexed { i, p ->
                if (i > 0) GroupDivider()
                ProviderRow(
                    rank = i + 1,
                    provider = p,
                    key = settings.keyFor(p.host),
                    canRemove = list.size > 1,
                    onUp = if (i > 0) ({ actions.editProviders(kind) { l -> moveUp(l, p) } }) else null,
                    onDown = if (i < list.size - 1) ({ actions.editProviders(kind) { l -> moveDown(l, p) } }) else null,
                    onKey = { keyFor = p },
                    onRemove = { actions.editProviders(kind) { l -> l.filterNot { it.sameAs(p) } } },
                )
            }
            if (list.size < ProviderPresets.MAX_PROVIDERS) AddRow(stringResource(R.string.providers_add)) { adding = true }
        }
        Text(stringResource(R.string.providers_hint), style = VolozType.Caption, color = VolozColors.Graphite, modifier = Modifier.padding(horizontal = 4.dp))
    }

    keyFor?.let { p -> KeyDialog(p, settings, actions, onDismiss = { keyFor = null }) }
    if (adding) {
        AddProviderDialog(
            kind = kind,
            existing = list,
            onDismiss = { adding = false },
            onAdd = { p ->
                adding = false
                actions.editProviders(kind) { it + p }
                // A new host without a key: ask for it right away.
                if (settings.keyFor(p.host) == null) keyFor = p
            },
        )
    }
}

/** [p] one place up; the list as is when [p] is already first or gone. */
internal fun moveUp(list: List<Provider>, p: Provider): List<Provider> {
    val i = list.indexOfFirst { it.sameAs(p) }
    return if (i > 0) list.toMutableList().apply { add(i - 1, removeAt(i)) } else list
}

/** [p] one place down; the list as is when [p] is already last or gone. */
internal fun moveDown(list: List<Provider>, p: Provider): List<Provider> {
    val i = list.indexOfFirst { it.sameAs(p) }
    return if (i in 0 until list.size - 1) list.toMutableList().apply { add(i + 1, removeAt(i)) } else list
}

@Composable
private fun ProviderRow(
    rank: Int,
    provider: Provider,
    key: SavedKey?,
    canRemove: Boolean,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onKey: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RankBadge(rank, main = rank == 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(provider.displayName, style = VolozType.RowTitle, color = VolozColors.Ink)
            Text(provider.model, style = VolozType.Mono, color = VolozColors.Graphite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusPill(
                if (key != null) stringResource(R.string.providers_key_ok, KeyRules.mask(key.key)) else stringResource(R.string.providers_key_missing),
                if (key != null) PillTone.OK else PillTone.WARN,
                onClick = onKey,
            )
        }
        if (onUp != null) SquareControl(VolozIcons.Up, stringResource(R.string.providers_up), onUp)
        Box {
            SquareControl(VolozIcons.More, stringResource(R.string.providers_more)) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = VolozColors.Sheet) {
                DropdownMenuItem(text = { Text(stringResource(R.string.providers_key_menu)) }, onClick = { menu = false; onKey() })
                if (onDown != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.providers_down)) }, onClick = { menu = false; onDown() })
                }
                if (canRemove) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.providers_remove)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

/** Presets not in the list yet, fastest first, then any OpenAI-compatible service. */
@Composable
private fun AddProviderDialog(kind: ProviderKind, existing: List<Provider>, onDismiss: () -> Unit, onAdd: (Provider) -> Unit) {
    val presets = (if (kind == ProviderKind.STT) ProviderPresets.STT else ProviderPresets.REFINE).filter { p -> existing.none { it.sameAs(p) } }
    var custom by rememberSaveable { mutableStateOf(false) }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VolozColors.Sheet,
        title = { Text(stringResource(R.string.providers_add), style = VolozType.CardTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!custom) {
                    for (p in presets) {
                        Row(
                            Modifier.fillMaxWidth().clip(VolozShapes.Field).border(1.dp, VolozColors.Hair, VolozShapes.Field)
                                .clickable { onAdd(p) }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.displayName, style = VolozType.RowTitle, color = VolozColors.Ink)
                                Text(p.model, style = VolozType.Mono, color = VolozColors.Graphite)
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.providers_other),
                        style = VolozType.Body.copy(fontWeight = FontWeight.Medium), color = VolozColors.Graphite,
                        modifier = Modifier.fillMaxWidth().clip(VolozShapes.Field).clickable { custom = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                } else {
                    Eyebrow(stringResource(R.string.providers_endpoint))
                    VolozTextField(endpoint, { endpoint = it }, placeholder = "https://api.exemplo.com/v1", textStyle = VolozType.Mono.copy(fontSize = VolozType.Caption.fontSize),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                    Eyebrow(stringResource(R.string.providers_model))
                    VolozTextField(model, { model = it }, textStyle = VolozType.Mono.copy(fontSize = VolozType.Caption.fontSize),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (custom) {
                val p = Provider(endpoint.trim(), model.trim())
                TextButton(onClick = { onAdd(p) }, enabled = p.host.isNotEmpty() && p.model.isNotEmpty() && p.endpoint.startsWith("https://")) {
                    Text(stringResource(R.string.common_add), color = VolozColors.Ink, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = VolozColors.Graphite) } },
    )
}

/**
 * The keys kept for one host: pick the one in use, paste a new one (tested
 * against the provider, refused when its prefix names another provider) or
 * forget one (asks first).
 */
@Composable
private fun KeyDialog(provider: Provider, settings: VolozSettings, actions: ProviderActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val keys = settings.keysFor(provider.host)
    val active = settings.keyFor(provider.host)
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var forget by remember { mutableStateOf<SavedKey?>(null) }

    fun save(test: Boolean) {
        busy = true
        error = null
        scope.launch {
            when (val r = actions.saveKey(provider, input, test)) {
                KeySaveResult.Ok -> { input = ""; onDismiss() }
                is KeySaveResult.Failed -> error = r.message
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VolozColors.Sheet,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.key_dialog_title, provider.displayName), style = VolozType.CardTitle)
                Text(provider.host, style = VolozType.Mono, color = VolozColors.Graphite)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (keys.isNotEmpty()) Eyebrow(stringResource(R.string.key_dialog_saved))
                for (k in keys) {
                    val inUse = k.id == active?.id
                    Row(
                        Modifier.fillMaxWidth().clip(VolozShapes.Field)
                            .border(1.dp, if (inUse) VolozColors.Ink else VolozColors.Hair, VolozShapes.Field)
                            .clickable { actions.selectKey(provider.host, k.id) }
                            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(CircleShape)
                                .border(if (inUse) 5.dp else 1.dp, if (inUse) VolozColors.Ink else VolozColors.Hair, CircleShape),
                        )
                        Text(KeyRules.mask(k.key), style = VolozType.MonoKey.copy(fontSize = VolozType.Caption.fontSize), color = VolozColors.Ink, modifier = Modifier.weight(1f))
                        TextButton(onClick = { forget = k }) { Text(stringResource(R.string.key_dialog_forget), color = VolozColors.Graphite) }
                    }
                }
                Eyebrow(stringResource(if (keys.isEmpty()) R.string.key_dialog_paste else R.string.key_dialog_paste_other), Modifier.padding(top = 6.dp))
                VolozTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    placeholder = stringResource(R.string.key_placeholder),
                    textStyle = VolozType.MonoKey,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = VolozType.Caption, color = VolozColors.Signal) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        stringResource(if (busy) R.string.key_testing else R.string.key_test_save),
                        { save(test = true) }, enabled = input.isNotBlank() && !busy,
                    )
                    SecondaryButton(stringResource(R.string.key_save), { save(test = false) }, enabled = input.isNotBlank() && !busy)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done), color = VolozColors.Ink, fontWeight = FontWeight.SemiBold) } },
    )

    forget?.let { k ->
        AlertDialog(
            onDismissRequest = { forget = null },
            containerColor = VolozColors.Sheet,
            title = { Text(stringResource(R.string.key_forget_title), style = VolozType.CardTitle) },
            text = { Text(stringResource(R.string.key_forget_body, KeyRules.mask(k.key), provider.host), style = VolozType.Body) },
            confirmButton = {
                TextButton(onClick = { actions.forgetKey(k.id); forget = null }) {
                    Text(stringResource(R.string.key_dialog_forget), color = VolozColors.Signal, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.common_cancel), color = VolozColors.Graphite) } },
        )
    }
}

/** Status line under the top bar: "Groq · whisper-large-v3 → gpt-oss-120b". */
fun modelStatusLine(settings: VolozSettings): String {
    val stt = settings.sttProviders.first()
    val refine = settings.refineProviders.firstOrNull()?.takeIf { settings.refine }
    return buildString {
        append(stt.displayName).append(" · ").append(stt.shortModel)
        if (refine != null) append(" → ").append(refine.shortModel)
    }
}

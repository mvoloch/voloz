package io.github.mvoloch.voloz.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class HistoryEntry(
    val text: String,
    val raw: String,
    val timestamp: Long,
    val refined: Boolean,
    /** Package of the app the text went into, or null when it was only copied. */
    val targetPackage: String?,
    /** Length of the speech (0 for entries from before it was kept). */
    val durationMs: Long = 0,
    /** The model that refined it, and whether that was a backup provider. */
    val refineModel: String? = null,
    val refineBackup: Boolean = false,
    /** The text came from a text shortcut. */
    val shortcut: Boolean = false,
    /** Corrected by hand in the history. */
    val edited: Boolean = false,
)

private val Context.historyStore by preferencesDataStore(name = "history")

/** Last [MAX] dictations, newest first, kept on this device only. */
class HistoryRepository(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.historyStore
    private val key = stringPreferencesKey("entries")

    companion object {
        const val MAX = 200
    }

    val entries: StateFlow<List<HistoryEntry>> = store.data
        .catch { emit(emptyPreferences()) }
        .map { decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(entry: HistoryEntry) {
        scope.launch {
            store.edit { prefs -> prefs[key] = encode((listOf(entry) + decode(prefs[key])).take(MAX)) }
        }
    }

    /** Changes the entry with this [timestamp] (the history's key), read from the store. */
    fun update(timestamp: Long, change: (HistoryEntry) -> HistoryEntry) {
        scope.launch {
            store.edit { prefs -> prefs[key] = encode(decode(prefs[key]).map { if (it.timestamp == timestamp) change(it) else it }) }
        }
    }

    fun remove(timestamp: Long) {
        scope.launch { store.edit { prefs -> prefs[key] = encode(decode(prefs[key]).filterNot { it.timestamp == timestamp }) } }
    }

    fun clear() {
        scope.launch { store.edit { it.remove(key) } }
    }

    private fun encode(list: List<HistoryEntry>): String = buildJsonArray {
        for (e in list) {
            add(
                JsonObject(
                    buildMap {
                        put("text", JsonPrimitive(e.text))
                        put("raw", JsonPrimitive(e.raw))
                        put("ts", JsonPrimitive(e.timestamp))
                        put("refined", JsonPrimitive(e.refined))
                        e.targetPackage?.let { put("pkg", JsonPrimitive(it)) }
                        if (e.durationMs > 0) put("ms", JsonPrimitive(e.durationMs))
                        e.refineModel?.let { put("rmodel", JsonPrimitive(it)) }
                        if (e.refineBackup) put("rbackup", JsonPrimitive(true))
                        if (e.shortcut) put("shortcut", JsonPrimitive(true))
                        if (e.edited) put("edited", JsonPrimitive(true))
                    },
                ),
            )
        }
    }.toString()

    private fun decode(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val text = o["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                HistoryEntry(
                    text = text,
                    raw = o["raw"]?.jsonPrimitive?.contentOrNull ?: text,
                    timestamp = o["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                    refined = o["refined"]?.jsonPrimitive?.booleanOrNull ?: false,
                    targetPackage = o["pkg"]?.jsonPrimitive?.contentOrNull,
                    durationMs = o["ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                    refineModel = o["rmodel"]?.jsonPrimitive?.contentOrNull,
                    refineBackup = o["rbackup"]?.jsonPrimitive?.booleanOrNull ?: false,
                    shortcut = o["shortcut"]?.jsonPrimitive?.booleanOrNull ?: false,
                    edited = o["edited"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        }.getOrDefault(emptyList())
    }
}

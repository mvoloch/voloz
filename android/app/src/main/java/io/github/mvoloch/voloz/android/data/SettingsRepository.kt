package io.github.mvoloch.voloz.android.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mvoloch.voloz.android.net.KeyRules
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.refine.RefinementPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

enum class BubbleSize(val dp: Int) { SMALL(48), MEDIUM(60), LARGE(72) }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ProviderKind { STT, REFINE }

/** An API key kept for a host. Every key ever set is kept until the user forgets it. */
data class SavedKey(val id: String, val host: String, val key: String, val addedAt: Long)

/** A key whose prefix names another provider than the host it was given for. */
class KeyMismatchException(val keyProvider: String, val host: String) :
    IllegalArgumentException("This looks like a $keyProvider key, but the endpoint is $host.")

data class VolozSettings(
    val savedKeys: List<SavedKey> = emptyList(),
    /** host -> id of the key picked for it; without a pick the newest key for the host is used. */
    val activeKeyIds: Map<String, String> = emptyMap(),
    /** Main provider first, then the backups in the order they are tried. */
    val sttProviders: List<Provider> = listOf(ProviderPresets.DEFAULT_STT),
    val refineProviders: List<Provider> = listOf(ProviderPresets.DEFAULT_REFINE),
    val refine: Boolean = true,
    val vocabulary: List<String> = emptyList(),
    /** Design's "Só em campos de texto": true = only on focused text fields with the keyboard up. */
    val onlyInTextFields: Boolean = true,
    val bubbleSize: BubbleSize = BubbleSize.MEDIUM,
    /** Light unless the user picks otherwise (owner's call, 2026-10-30), even on a phone in dark mode. */
    val theme: ThemeMode = ThemeMode.LIGHT,
) {
    fun keysFor(host: String): List<SavedKey> = savedKeys.filter { it.host == host }

    /** The key in use for [host]: the one picked, else the newest. */
    fun keyFor(host: String): SavedKey? = keysFor(host).let { keys -> keys.firstOrNull { it.id == activeKeyIds[host] } ?: keys.lastOrNull() }

    fun keyFor(provider: Provider): String? = keyFor(provider.host)?.key

    fun providers(kind: ProviderKind): List<Provider> = if (kind == ProviderKind.STT) sttProviders else refineProviders

    /** The main transcription provider has a key: dictation can start. */
    val hasMainKey: Boolean get() = sttProviders.firstOrNull()?.let { keyFor(it) } != null
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.settingsStore
    private val cipher = KeyCipher()
    /** Plaintext per stored blob: settings are re-read on every change, and each decrypt is a Keystore call. */
    private val plain = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun decrypt(blob: String): String? = plain[blob] ?: cipher.decrypt(blob)?.also { plain[blob] = it }
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        /** Prototype's single Groq key; read into the saved keys, dropped on the first key change. */
        val LEGACY_GROQ_KEY = stringPreferencesKey("groq_key_enc")
        const val LEGACY_GROQ_HOST = "api.groq.com"
        val SAVED_KEYS = stringPreferencesKey("saved_keys")
        val ACTIVE_KEYS = stringPreferencesKey("active_keys")
        val STT_PROVIDERS = stringPreferencesKey("stt_providers")
        val REFINE_PROVIDERS = stringPreferencesKey("refine_providers")
        val REFINE = booleanPreferencesKey("refine")
        val VOCABULARY = stringPreferencesKey("vocabulary")
        val ONLY_TEXT_FIELDS = booleanPreferencesKey("only_text_fields")
        val BUBBLE_SIZE = stringPreferencesKey("bubble_size")
        val THEME = stringPreferencesKey("theme")
    }

    /** Null until the first read finishes (lets the UI pick its start screen without a flash). */
    val settings: StateFlow<VolozSettings?> = store.data
        .catch { emit(emptyPreferences()) }
        .map { toSettings(it) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    val current: VolozSettings get() = settings.value ?: VolozSettings()

    /** A stored key: [enc] is the Keystore-encrypted blob, as kept in DataStore. */
    private data class StoredKey(val id: String, val host: String, val enc: String, val addedAt: Long)

    private fun storedKeys(p: Preferences): List<StoredKey> {
        val raw = p[SAVED_KEYS]
        if (raw == null) {
            val legacy = p[LEGACY_GROQ_KEY]?.takeIf { it.isNotEmpty() } ?: return emptyList()
            return listOf(StoredKey("groq0", LEGACY_GROQ_HOST, legacy, 0L))
        }
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                StoredKey(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    host = o["host"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    enc = o["enc"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    addedAt = o["added"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeKeys(prefs: MutablePreferences, keys: List<StoredKey>) {
        prefs[SAVED_KEYS] = buildJsonArray {
            for (k in keys) {
                add(
                    buildJsonObject {
                        put("id", k.id)
                        put("host", k.host)
                        put("enc", k.enc)
                        put("added", k.addedAt)
                    },
                )
            }
        }.toString()
        prefs.remove(LEGACY_GROQ_KEY)
    }

    private fun activeKeys(p: Preferences): Map<String, String> = runCatching {
        json.parseToJsonElement(p[ACTIVE_KEYS] ?: return emptyMap()).jsonObject
            .mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    private fun writeActive(prefs: MutablePreferences, active: Map<String, String>) {
        prefs[ACTIVE_KEYS] = JsonObject(active.mapValues { JsonPrimitive(it.value) }).toString()
    }

    private fun providers(raw: String?, default: Provider): List<Provider> {
        if (raw == null) return listOf(default)
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                Provider(
                    endpoint = o["endpoint"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    model = o["model"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(default)
    }

    private fun toSettings(p: Preferences) = VolozSettings(
        // A blob the Keystore can't read any more (key wiped) is left out, not shown as a broken key.
        savedKeys = storedKeys(p).mapNotNull { k -> decrypt(k.enc)?.let { SavedKey(k.id, k.host, it, k.addedAt) } },
        activeKeyIds = activeKeys(p),
        sttProviders = providers(p[STT_PROVIDERS], ProviderPresets.DEFAULT_STT),
        refineProviders = providers(p[REFINE_PROVIDERS], ProviderPresets.DEFAULT_REFINE),
        refine = p[REFINE] ?: true,
        vocabulary = p[VOCABULARY]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
        onlyInTextFields = p[ONLY_TEXT_FIELDS] ?: true,
        bubbleSize = p[BUBBLE_SIZE]?.let { runCatching { BubbleSize.valueOf(it) }.getOrNull() } ?: BubbleSize.MEDIUM,
        theme = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
    )

    /**
     * Keeps [key] for [host] and makes it the one in use there. A key already kept
     * is only picked again. Throws [KeyMismatchException] when its prefix names
     * another provider (a Claude key pasted for Groq).
     */
    suspend fun addKey(host: String, key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty() && host.isNotBlank())
        KeyRules.mismatch(trimmed, host)?.let { throw KeyMismatchException(it, host) }
        val known = current.savedKeys.firstOrNull { it.host == host && it.key == trimmed }
        val blob = if (known == null) cipher.encrypt(trimmed).also { plain[it] = trimmed } else null
        store.edit { prefs ->
            val keys = storedKeys(prefs)
            val id = known?.id ?: newId()
            if (blob != null) writeKeys(prefs, keys + StoredKey(id, host, blob, System.currentTimeMillis()))
            else if (prefs[SAVED_KEYS] == null) writeKeys(prefs, keys)
            writeActive(prefs, activeKeys(prefs) + (host to id))
        }
    }

    suspend fun selectKey(host: String, id: String) = store.edit { prefs -> writeActive(prefs, activeKeys(prefs) + (host to id)) }

    suspend fun forgetKey(id: String) = store.edit { prefs ->
        val (gone, kept) = storedKeys(prefs).partition { it.id == id }
        gone.forEach { plain.remove(it.enc) }
        writeKeys(prefs, kept)
        writeActive(prefs, activeKeys(prefs).filterValues { it != id })
    }

    /**
     * Changes a provider list from what is stored, not from a screen's snapshot, so two
     * quick taps both count. An empty result is ignored: there is always a main provider.
     */
    suspend fun editProviders(kind: ProviderKind, change: (List<Provider>) -> List<Provider>) = store.edit { prefs ->
        val key = if (kind == ProviderKind.STT) STT_PROVIDERS else REFINE_PROVIDERS
        val default = if (kind == ProviderKind.STT) ProviderPresets.DEFAULT_STT else ProviderPresets.DEFAULT_REFINE
        val next = change(providers(prefs[key], default))
            .distinctBy { it.baseUrl + "|" + it.model }
            .take(ProviderPresets.MAX_PROVIDERS)
        if (next.isEmpty()) return@edit
        prefs[key] = buildJsonArray {
            for (p in next) add(buildJsonObject { put("endpoint", p.endpoint.trim()); put("model", p.model.trim()) })
        }.toString()
    }

    suspend fun setRefine(on: Boolean) = store.edit { it[REFINE] = on }
    suspend fun setOnlyInTextFields(on: Boolean) = store.edit { it[ONLY_TEXT_FIELDS] = on }
    suspend fun setBubbleSize(size: BubbleSize) = store.edit { it[BUBBLE_SIZE] = size.name }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[THEME] = mode.name }

    suspend fun addTerm(term: String) = editVocabulary { it + term }

    suspend fun removeTerm(term: String) = editVocabulary { it - term }

    /** Read-modify-write inside the store: removing two chips quickly removes both. */
    private suspend fun editVocabulary(change: (List<String>) -> List<String>) = store.edit { prefs ->
        val current = prefs[VOCABULARY]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        prefs[VOCABULARY] = RefinementPrompt.normalizeVocabulary(change(current)).joinToString("\n")
    }

    private fun newId(): String {
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

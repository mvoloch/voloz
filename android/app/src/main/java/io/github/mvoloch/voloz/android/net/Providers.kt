package io.github.mvoloch.voloz.android.net

import java.io.IOException
import java.net.URI

/** One transcription or refinement service: an OpenAI-style base URL (up to `/v1`) and a model. */
data class Provider(val endpoint: String, val model: String) {
    val host: String get() = hostOf(endpoint)

    /** The endpoint without spaces or a trailing slash: what requests are built on and providers compared by. */
    val baseUrl: String get() = baseUrlOf(endpoint)

    val vendor: Vendor get() = Vendor.of(host)

    /** "Groq", "OpenAI"… for known hosts, the host itself otherwise. */
    val displayName: String get() = vendor.label ?: host

    /** "qwen/qwen3.8-27b" -> "qwen3.8-27b": the vendor prefix is noise on screen. */
    val shortModel: String get() = model.substringAfterLast('/')

    /** Same endpoint (ignoring a trailing slash) and model. */
    fun sameAs(other: Provider): Boolean = baseUrl == other.baseUrl && model == other.model
}

enum class Vendor(val label: String?, val domain: String?) {
    GROQ("Groq", "groq.com"),
    OPENAI("OpenAI", "openai.com"),
    ANTHROPIC("Anthropic", "anthropic.com"),
    OPENROUTER("OpenRouter", "openrouter.ai"),
    OTHER(null, null);

    companion object {
        fun of(host: String): Vendor =
            entries.firstOrNull { it.domain != null && (host == it.domain || host.endsWith(".${it.domain}")) } ?: OTHER
    }
}

fun baseUrlOf(endpoint: String): String = endpoint.trim().trimEnd('/')

/** Host of an endpoint URL, lowercased; empty when it doesn't parse. */
fun hostOf(endpoint: String): String =
    runCatching { URI(endpoint.trim()).host?.lowercase() }.getOrNull().orEmpty()

/** The desktop's presets (app/src/lib/providers.ts BACKUP_PRESETS), fastest first, plus the Groq default. */
object ProviderPresets {
    const val GROQ = "https://api.groq.com/openai/v1"
    const val OPENAI = "https://api.openai.com/v1"
    const val ANTHROPIC = "https://api.anthropic.com/v1"

    /** Main plus up to four backups, as on the desktop. */
    const val MAX_PROVIDERS = 5

    val STT = listOf(
        Provider(GROQ, "whisper-large-v3"),
        Provider(OPENAI, "gpt-transcribe"),
    )

    /** gpt-oss-120b first: the desktop's Groq-only default (quick_setup.GROQ_LLM_MODEL). */
    val REFINE = listOf(
        Provider(GROQ, "openai/gpt-oss-120b"),
        Provider(GROQ, "qwen/qwen3.8-27b"),
        Provider(OPENAI, "gpt-6-luna"),
        Provider(ANTHROPIC, "claude-haiku-5-5"),
    )

    val DEFAULT_STT: Provider get() = STT[0]
    val DEFAULT_REFINE: Provider get() = REFINE[0]
}

/** Key/host checks and masking, as in the desktop's services/settings.py. */
object KeyRules {
    // Key prefixes that name their provider for sure. A bare "sk-" is used by
    // OpenAI and others, so it is not checked.
    private val PREFIX_HOSTS = listOf(
        Triple("sk-ant-", "anthropic.com", "Anthropic"),
        Triple("gsk_", "groq.com", "Groq"),
        Triple("sk-or-", "openrouter.ai", "OpenRouter"),
    )

    /** The provider [key] belongs to when its prefix names one other than [host]'s; null when it fits or can't tell. */
    fun mismatch(key: String, host: String): String? {
        if (key.isBlank() || host.isBlank()) return null
        for ((prefix, domain, name) in PREFIX_HOSTS) {
            if (key.startsWith(prefix) && host != domain && !host.endsWith(".$domain")) return name
        }
        return null
    }

    /** "gsk_ab…wxyz": never the whole key on screen. */
    fun mask(key: String): String =
        if (key.length <= 12) "…" + key.takeLast(2) else key.take(6) + "…" + key.takeLast(4)
}

/** The user cancelled the dictation, or it ran out of time: no further provider is tried. */
class DictationCancelled : Exception("dictation cancelled")

/**
 * A provider failed in a way another provider might not: an HTTP error status,
 * a timeout or an unreachable host ([status] null), or no key for its host
 * ([missingKey]). The next provider in the list gets the same request.
 */
class ProviderException(
    val provider: Provider?,
    val status: Int?,
    val detail: String?,
    val retryAfterSeconds: Double? = null,
    val missingKey: Boolean = false,
    cause: Throwable? = null,
) : IOException(
    buildString {
        append(provider?.let { "${it.model} @ ${it.host}" } ?: "provider")
        append(
            when {
                missingKey -> ": no key saved for this host"
                status != null -> " returned HTTP $status"
                else -> " is unreachable"
            },
        )
        detail?.let { append(": ").append(it) }
    },
    cause,
)

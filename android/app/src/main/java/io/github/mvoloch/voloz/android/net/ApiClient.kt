package io.github.mvoloch.voloz.android.net

import io.github.mvoloch.voloz.android.refine.RefinementPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Audio as uploaded: FLAC when it encoded, WAV otherwise. */
class UploadAudio(val bytes: ByteArray, val fileName: String, val mime: String)

/**
 * Blocking client for every provider (call off the main thread). Pure JVM: also
 * used by the end-to-end unit test. One [OkHttpClient] for the whole app, so each
 * provider's host keeps one pooled connection (HTTP/2 where the host offers it)
 * across the transcription, the refinement and the next dictation; [warm] opens
 * it while the user is still speaking.
 *
 * OpenAI-compatible hosts (Groq, OpenAI, OpenRouter…) get `/audio/transcriptions`
 * and `/chat/completions`; Anthropic gets its Messages API.
 */
class ApiClient(private val http: OkHttpClient = defaultHttpClient()) {
    companion object {
        const val MAX_TOKENS = 2048
        const val TEMPERATURE = 0.2
        const val ANTHROPIC_VERSION = "2023-06-01"
        /** Desktop anthropic_backend.py: effort "low" and room for its thinking on top of the text. */
        const val ANTHROPIC_EFFORT = "low"
        const val THINKING_HEADROOM_TOKENS = 1024

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()

        /** Whether [endpoint] is the OpenAI API itself (its GPT-5/6 models need other parameters). */
        fun isOpenAiApi(endpoint: String): Boolean = hostOf(endpoint) == "api.openai.com"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** "endpoint|model" of OpenAI models that refused `reasoning_effort: "none"`; not sent to them again. */
    private val reasoningRefused: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun Request.Builder.auth(endpoint: String, key: String): Request.Builder =
        if (Vendor.of(hostOf(endpoint)) == Vendor.ANTHROPIC) {
            header("x-api-key", key).header("anthropic-version", ANTHROPIC_VERSION)
        } else {
            header("Authorization", "Bearer $key")
        }

    private fun base(endpoint: String) = endpoint.trim().trimEnd('/')

    /** GET /models; returns how many models the key can see. Throws [ProviderException] on a bad key. */
    fun testKey(endpoint: String, apiKey: String): Int {
        val request = Request.Builder().url("${base(endpoint)}/models").auth(endpoint, apiKey).get().build()
        return execute(null, request) { body ->
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.size ?: 0
        }
    }

    /**
     * Opens the pooled connection ahead of a dictation with a free request:
     * `HEAD /models` carries no body and is not billed, yet it pays the TCP + TLS
     * handshake while the user is still speaking. Best effort: errors are ignored.
     */
    fun warm(endpoint: String, apiKey: String?) {
        val request = Request.Builder().url("${base(endpoint)}/models")
            .apply { if (!apiKey.isNullOrBlank()) auth(endpoint, apiKey) }
            .head().build()
        runCatching { http.newCall(request).execute().close() }
    }

    /** POST /audio/transcriptions (json, temperature 0, language auto, vocabulary as prompt). */
    fun transcribe(provider: Provider, apiKey: String, audio: UploadAudio, prompt: String?): String {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
        if (!prompt.isNullOrBlank()) form.addFormDataPart("prompt", prompt)
        form.addFormDataPart("file", audio.fileName, audio.bytes.toRequestBody(audio.mime.toMediaType()))
        val request = Request.Builder()
            .url("${base(provider.endpoint)}/audio/transcriptions")
            .auth(provider.endpoint, apiKey)
            .post(form.build())
            .build()
        return execute(provider, request) { body ->
            json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("${provider.host} transcription reply had no text")
        }
    }

    /** The desktop refinement prompt on [provider]: tagged output for Claude, the keep-language rule for the rest. */
    fun refine(provider: Provider, apiKey: String, transcript: String, vocabulary: List<String>): String =
        if (provider.vendor == Vendor.ANTHROPIC) {
            refineAnthropic(provider, apiKey, transcript, vocabulary)
        } else {
            refineOpenAiCompatible(provider, apiKey, transcript, vocabulary)
        }

    private fun refineOpenAiCompatible(provider: Provider, apiKey: String, transcript: String, vocabulary: List<String>): String {
        val id = "${base(provider.endpoint)}|${provider.model}"
        val reasoningOff = isOpenAiApi(provider.endpoint) && id !in reasoningRefused
        var payload = chatPayload(provider, RefinementPrompt.buildMessages(transcript, vocabulary), reasoningOff)
        try {
            return execute(provider, chatRequest(provider, apiKey, payload)) { refinedText(it) }
        } catch (e: ProviderException) {
            // gpt-5 / gpt-5-mini only go down to "minimal", and models that don't
            // reason (gpt-4.1) refuse the field outright: drop it for good, once.
            if (!(reasoningOff && e.status == 400 && e.detail?.contains("reasoning_effort") == true)) throw e
            reasoningRefused += id
            payload = chatPayload(provider, RefinementPrompt.buildMessages(transcript, vocabulary), reasoningOff = false)
            return execute(provider, chatRequest(provider, apiKey, payload)) { refinedText(it) }
        }
    }

    private fun chatRequest(provider: Provider, apiKey: String, payload: JsonObject) = Request.Builder()
        .url("${base(provider.endpoint)}/chat/completions")
        .auth(provider.endpoint, apiKey)
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()

    /**
     * Chat completion body. The OpenAI API's GPT-5/6 models reject `max_tokens`
     * (they take `max_completion_tokens`) and reason at medium effort unless sent
     * `reasoning_effort: "none"`; Groq's gpt-oss models keep the prototype's
     * `reasoning_effort: "low"`; other models get the plain request.
     */
    internal fun chatPayload(provider: Provider, messages: JsonArray, reasoningOff: Boolean): JsonObject = buildJsonObject {
        val openAi = isOpenAiApi(provider.endpoint)
        put("model", provider.model)
        put("messages", messages)
        put("temperature", TEMPERATURE)
        put(if (openAi) "max_completion_tokens" else "max_tokens", MAX_TOKENS)
        when {
            openAi && reasoningOff -> put("reasoning_effort", "none")
            provider.vendor == Vendor.GROQ && provider.model.contains("gpt-oss") -> put("reasoning_effort", "low")
        }
    }

    /** The reply's text. A reply cut at max_tokens throws, so the caller falls back to the raw transcript. */
    internal fun refinedText(body: String): String {
        val choice = json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        if (choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull == "length") {
            throw IOException("Refinement reply was cut off at max_tokens")
        }
        val content = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return content?.trim()?.takeIf { it.isNotEmpty() } ?: throw IOException("Refinement reply was empty")
    }

    private fun refineAnthropic(provider: Provider, apiKey: String, transcript: String, vocabulary: List<String>): String {
        val request = Request.Builder()
            .url("${base(provider.endpoint)}/messages")
            .auth(provider.endpoint, apiKey)
            .post(anthropicPayload(provider, transcript, vocabulary).toString().toRequestBody("application/json".toMediaType()))
            .build()
        return execute(provider, request) { anthropicText(provider, it) }
    }

    /** Messages API body as the desktop's anthropic_backend sends it (no temperature: current Claude models reject it). */
    internal fun anthropicPayload(provider: Provider, transcript: String, vocabulary: List<String>): JsonObject = buildJsonObject {
        put("model", provider.model)
        put("max_tokens", MAX_TOKENS + THINKING_HEADROOM_TOKENS)
        put("system", RefinementPrompt.buildSystemPrompt(vocabulary, tagged = true))
        put(
            "messages",
            buildJsonArray {
                for ((user, assistant) in RefinementPrompt.taggedExamples()) {
                    add(message("user", user))
                    add(message("assistant", assistant))
                }
                add(message("user", transcript))
            },
        )
        putJsonObject("output_config") { put("effort", ANTHROPIC_EFFORT) }
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** Text blocks joined, then only what is inside the transcript tags. */
    internal fun anthropicText(provider: Provider, body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        when (root["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "refusal" -> throw IOException("Claude (${provider.model}) declined to process this text")
            "max_tokens" -> throw IOException("Claude (${provider.model}) stopped at max_tokens before finishing the text")
        }
        val text = root["content"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .trim()
        if (text.isEmpty()) throw IOException("No text in the Claude reply")
        return RefinementPrompt.extractTagged(text).takeIf { it.isNotEmpty() } ?: throw IOException("Refinement reply was empty")
    }

    /** Runs [request]; HTTP errors and network failures become [ProviderException], [read] parses a 2xx body. */
    private fun <T> execute(provider: Provider?, request: Request, read: (String) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ProviderException(provider, null, e.javaClass.simpleName, cause = e)
        }
        return response.use { read(readOk(provider, it)) }
    }

    private fun readOk(provider: Provider?, response: Response): String {
        val body = try {
            response.body?.string().orEmpty()
        } catch (e: IOException) {
            throw ProviderException(provider, null, e.javaClass.simpleName, cause = e)
        }
        if (!response.isSuccessful) {
            // The provider's own message (and the parameter it names), never the body:
            // some servers echo the whole prompt in their errors.
            val detail = runCatching {
                val error = json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
                val message = error?.get("message")?.jsonPrimitive?.contentOrNull
                val param = (error?.get("param") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                listOfNotNull(message, param?.let { "(param $it)" }).joinToString(" ").ifEmpty { null }
            }.getOrNull()
            throw ProviderException(
                provider,
                response.code,
                detail?.take(200),
                retryAfterSeconds = parseRetryAfter(response.header("retry-after")),
            )
        }
        return body
    }
}

/** Seconds from a `Retry-After` header ("7", "2.5"), null otherwise (desktop `parse_retry_after`). */
fun parseRetryAfter(value: String?): Double? = value?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

package io.github.mvoloch.voloz.android.net

import io.github.mvoloch.voloz.android.refine.RefinementPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A non-2xx reply from Groq. [detail] is Groq's own error message when it sent one. */
class GroqHttpException(val status: Int, val detail: String?) :
    IOException("Groq HTTP $status${detail?.let { ": $it" } ?: ""}")

/** Blocking Groq client (call off the main thread). Pure JVM: also used by the end-to-end unit test. */
class GroqClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = "https://api.groq.com/openai/v1",
) {
    companion object {
        const val STT_MODEL = "whisper-large-v3"
        const val REFINE_MODEL = "openai/gpt-oss-120b"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** GET /models; returns how many models the key can see. Throws on a bad key. */
    fun testKey(apiKey: String): Int {
        val request = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            val body = readOk(response)
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.size ?: 0
        }
    }

    /** POST /audio/transcriptions (whisper-large-v3, json, temperature 0, language auto). */
    fun transcribe(apiKey: String, wav: ByteArray, prompt: String?): String {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
        if (!prompt.isNullOrBlank()) form.addFormDataPart("prompt", prompt)
        form.addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
        val request = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(form.build())
            .build()
        return http.newCall(request).execute().use { response ->
            val body = readOk(response)
            json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("Groq transcription reply had no text")
        }
    }

    /** POST /chat/completions with the desktop refinement prompt (gpt-oss-120b, reasoning low). */
    fun refine(apiKey: String, transcript: String, vocabulary: List<String>): String {
        val payload = buildJsonObject {
            put("model", REFINE_MODEL)
            put("messages", RefinementPrompt.buildMessages(transcript, vocabulary))
            put("temperature", 0.2)
            put("max_tokens", 2048)
            put("reasoning_effort", "low")
        }
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            val body = readOk(response)
            val content = json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull
            content?.trim()?.takeIf { it.isNotEmpty() } ?: throw IOException("Groq refinement reply was empty")
        }
    }

    private fun readOk(response: Response): String {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching {
                (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)
                    ?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw GroqHttpException(response.code, detail?.take(200))
        }
        return body
    }
}

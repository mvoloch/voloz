package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.net.ApiClient
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.Fallback
import io.github.mvoloch.voloz.android.net.KeyRules
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderException
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.net.UploadAudio
import io.github.mvoloch.voloz.android.net.Vendor
import io.github.mvoloch.voloz.android.net.parseRetryAfter
import io.github.mvoloch.voloz.android.refine.RefinementPrompt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

private val GROQ_STT = Provider(ProviderPresets.GROQ, "whisper-large-v3")
private val OPENAI_STT = Provider(ProviderPresets.OPENAI, "gpt-transcribe")
private val GROQ_LLM = Provider(ProviderPresets.GROQ, "openai/gpt-oss-120b")
private val QWEN = Provider(ProviderPresets.GROQ, "qwen/qwen3.8-27b")
private val LUNA = Provider(ProviderPresets.OPENAI, "gpt-6-luna")
private val HAIKU = Provider(ProviderPresets.ANTHROPIC, "claude-haiku-5-5")

/** Answers requests from a script instead of the network, and keeps what was sent. */
private class FakeServer(private val answer: (Request, String) -> Triple<Int, String, Map<String, String>>) : Interceptor {
    val sent = mutableListOf<Pair<Request, String>>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        sent += request to body
        val (code, reply, headers) = answer(request, body)
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(reply.toResponseBody("application/json".toMediaType()))
            .build()
    }

    fun client() = ApiClient(OkHttpClient.Builder().addInterceptor(this).build())
}

private fun chatReply(text: String) =
    """{"choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}"""

private val json = Json

private fun bodyJson(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

class ProvidersTest {
    // ---- providers and keys ------------------------------------------------

    @Test
    fun vendorsAndNamesComeFromTheHost() {
        assertEquals(Vendor.GROQ, GROQ_STT.vendor)
        assertEquals("Groq", GROQ_STT.displayName)
        assertEquals("OpenAI", LUNA.displayName)
        assertEquals("Anthropic", HAIKU.displayName)
        assertEquals("qwen3.8-27b", QWEN.shortModel)
        assertEquals("llm.example.org", Provider("https://llm.example.org/v1", "m").displayName)
        assertTrue(Provider("https://api.groq.com/openai/v1/", "m").sameAs(Provider("https://api.groq.com/openai/v1", "m")))
    }

    @Test
    fun aKeyNamingAnotherProviderIsRefused() {
        assertEquals("Anthropic", KeyRules.mismatch("sk-ant-api03-abc", "api.openai.com"))
        assertEquals("Groq", KeyRules.mismatch("gsk_abc", "api.openai.com"))
        assertEquals("OpenRouter", KeyRules.mismatch("sk-or-v1-abc", "api.groq.com"))
        assertNull(KeyRules.mismatch("gsk_abc", "api.groq.com"))
        assertNull(KeyRules.mismatch("sk-ant-abc", "api.anthropic.com"))
        // A bare sk- is OpenAI's and others': not checked.
        assertNull(KeyRules.mismatch("sk-proj-abc", "api.groq.com"))
    }

    @Test
    fun keysAreMaskedLikeTheDesktop() {
        assertEquals("gsk_TW…V8hO", KeyRules.mask("gsk_TWxxxxxxxxxxxxxxV8hO"))
        assertEquals("…ab", KeyRules.mask("short-ab"))
    }

    @Test
    fun retryAfterIsSecondsOnly() {
        assertEquals(7.0, parseRetryAfter("7")!!, 0.0)
        assertEquals(2.5, parseRetryAfter(" 2.5 ")!!, 0.0)
        assertNull(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(parseRetryAfter("-1"))
        assertNull(parseRetryAfter(null))
    }

    // ---- fallback ------------------------------------------------------------

    @Test
    fun backupsAreTriedInOrderOnProviderErrors() {
        val f = Fallback { 0L }
        val tried = mutableListOf<String>()
        val answer = f.run("llm", listOf(GROQ_LLM, LUNA, HAIKU)) { p ->
            tried += p.model
            if (p != HAIKU) throw ProviderException(p, if (p == GROQ_LLM) 500 else null, null)
            "ok"
        }
        assertEquals(listOf("openai/gpt-oss-120b", "gpt-6-luna", "claude-haiku-5-5"), tried)
        assertEquals(HAIKU, answer.provider)
        assertTrue(answer.backup)
        assertEquals(2, answer.failedBefore)
    }

    @Test
    fun otherErrorsAreNotRetriedElsewhere() {
        val f = Fallback { 0L }
        val tried = mutableListOf<Provider>()
        try {
            f.run("llm", listOf(GROQ_LLM, LUNA)) { p -> tried += p; throw IOException("empty reply") }
            fail()
        } catch (e: IOException) {
            assertFalse(e is ProviderException)
        }
        assertEquals(listOf(GROQ_LLM), tried)
    }

    @Test
    fun aRateLimitedProviderCoolsDownThenComesBack() {
        var now = 0L
        val f = Fallback { now }
        val tried = mutableListOf<String>()
        fun dictate() = f.run("stt", listOf(GROQ_STT, OPENAI_STT)) { p ->
            tried += p.model
            if (p == GROQ_STT && now < 30_000) throw ProviderException(p, 429, "rate limit", retryAfterSeconds = 20.0)
            p.model
        }
        assertEquals("gpt-transcribe", dictate().value) // Groq 429s, OpenAI answers
        now = 10_000
        tried.clear()
        assertEquals("gpt-transcribe", dictate().value)
        assertEquals(listOf("gpt-transcribe"), tried) // Groq skipped while parked
        now = 21_000
        tried.clear()
        dictate() // parked again (still < 30 s on the fake server)
        assertEquals(listOf("whisper-large-v3", "gpt-transcribe"), tried)
        now = 60_000
        tried.clear()
        val answer = dictate()
        assertEquals("whisper-large-v3", answer.value)
        assertFalse(answer.backup)
    }

    @Test
    fun cooldownDefaultsToAMinuteAndIsCapped() {
        var now = 0L
        val f = Fallback { now }
        runCatching { f.run("stt", listOf(GROQ_STT, OPENAI_STT)) { p -> throw ProviderException(p, 429, null, if (p == GROQ_STT) null else 99_999.0) } }
        now = 59_000
        assertEquals(listOf(GROQ_STT, OPENAI_STT), f.order("stt", listOf(GROQ_STT, OPENAI_STT))) // all parked: all tried
        now = 61_000
        assertEquals(listOf(GROQ_STT), f.order("stt", listOf(GROQ_STT, OPENAI_STT)))
        now = 601_000
        assertEquals(listOf(GROQ_STT, OPENAI_STT), f.order("stt", listOf(GROQ_STT, OPENAI_STT)))
    }

    // ---- request shapes --------------------------------------------------------

    @Test
    fun openAiGetsMaxCompletionTokensAndNoReasoning() {
        val server = FakeServer { _, _ -> Triple(200, chatReply("Olá."), emptyMap()) }
        assertEquals("Olá.", server.client().refine(LUNA, "k", "olá", emptyList()))
        val (req, body) = server.sent.single()
        val b = bodyJson(body)
        assertEquals("https://api.openai.com/v1/chat/completions", req.url.toString())
        assertEquals("Bearer k", req.header("Authorization"))
        assertEquals("2048", b["max_completion_tokens"]!!.jsonPrimitive.content)
        assertNull(b["max_tokens"])
        assertEquals("none", b["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("0.2", b["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun aModelThatRefusesReasoningNoneIsAskedAgainWithoutItOnce() {
        val server = FakeServer { _, body ->
            if ("reasoning_effort" in body) {
                Triple(400, """{"error":{"message":"Unsupported value: 'none'","param":"reasoning_effort"}}""", emptyMap())
            } else {
                Triple(200, chatReply("ok"), emptyMap())
            }
        }
        val client = server.client()
        val gpt5 = Provider(ProviderPresets.OPENAI, "gpt-5-mini")
        assertEquals("ok", client.refine(gpt5, "k", "x", emptyList()))
        assertEquals("ok", client.refine(gpt5, "k", "x", emptyList()))
        assertEquals(3, server.sent.size) // refused once, then never sent again
    }

    @Test
    fun groqKeepsMaxTokensWithLowReasoningForGptOssOnly() {
        val server = FakeServer { _, _ -> Triple(200, chatReply("ok"), emptyMap()) }
        val client = server.client()
        client.refine(GROQ_LLM, "k", "x", emptyList())
        client.refine(QWEN, "k", "x", emptyList())
        val (oss, qwen) = server.sent.map { bodyJson(it.second) }
        assertEquals("2048", oss["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("low", oss["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("2048", qwen["max_tokens"]!!.jsonPrimitive.content)
        assertNull(qwen["reasoning_effort"])
        assertNull(qwen["max_completion_tokens"])
        // Non-Claude models get the keep-language rule and plain examples.
        val system = qwen["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.endsWith("Never translate."))
    }

    @Test
    fun claudeGetsTheMessagesApiAndTaggedOutput() {
        val reply = """{"content":[{"type":"thinking","thinking":"…"},{"type":"text","text":"<transcript>Olá, tudo bem?</transcript>\n\nCleaning up: removed fillers."}],"stop_reason":"end_turn"}"""
        val server = FakeServer { _, _ -> Triple(200, reply, emptyMap()) }
        assertEquals("Olá, tudo bem?", server.client().refine(HAIKU, "sk-ant-x", "olá tudo bem", listOf("Voloz")))
        val (req, body) = server.sent.single()
        assertEquals("https://api.anthropic.com/v1/messages", req.url.toString())
        assertEquals("sk-ant-x", req.header("x-api-key"))
        assertEquals("2023-06-01", req.header("anthropic-version"))
        assertNull(req.header("Authorization"))
        val b = bodyJson(body)
        assertEquals("3072", b["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("low", b["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(b["temperature"])
        assertTrue(b["system"]!!.jsonPrimitive.content.endsWith(RefinementPrompt.TAGGED_OUTPUT_INSTRUCTION))
        val messages = b["messages"]!!.jsonArray
        assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.content.startsWith("<transcript>"))
        assertEquals("olá tudo bem", messages.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test(expected = IOException::class)
    fun aClaudeRefusalIsNotInserted() {
        val server = FakeServer { _, _ -> Triple(200, """{"content":[],"stop_reason":"refusal"}""", emptyMap()) }
        server.client().refine(HAIKU, "k", "x", emptyList())
    }

    @Test
    fun httpErrorsCarryStatusAndRetryAfter() {
        val server = FakeServer { _, _ -> Triple(429, """{"error":{"message":"Rate limit reached"}}""", mapOf("retry-after" to "12")) }
        try {
            server.client().refine(QWEN, "k", "x", emptyList())
            fail()
        } catch (e: ProviderException) {
            assertEquals(429, e.status)
            assertEquals(12.0, e.retryAfterSeconds!!, 0.0)
            assertEquals("Rate limit reached", e.detail)
        }
    }

    @Test
    fun theUploadNamesItsFormat() {
        val server = FakeServer { _, _ -> Triple(200, """{"text":" oi "}""", emptyMap()) }
        server.client().transcribe(GROQ_STT, "k", UploadAudio(byteArrayOf(1, 2), "audio.flac", "audio/flac"), "Voloz")
        val body = server.sent.single().second
        assertTrue(body.contains("filename=\"audio.flac\""))
        assertTrue(body.contains("Content-Type: audio/flac"))
        assertTrue(body.contains("whisper-large-v3"))
    }

    // ---- the pipeline ---------------------------------------------------------

    @Test
    fun aDictationFallsBackAcrossHostsWithEachHostsKey() {
        val server = FakeServer { req, _ ->
            when {
                req.url.host == "api.groq.com" -> Triple(429, """{"error":{"message":"tpm"}}""", emptyMap())
                req.url.encodedPath.endsWith("/audio/transcriptions") -> Triple(200, """{"text":"oi tudo bem"}""", emptyMap())
                else -> Triple(200, chatReply("Oi, tudo bem?"), emptyMap())
            }
        }
        val keys = mapOf("api.groq.com" to "gsk_1", "api.openai.com" to "sk-2")
        val route = DictationPipeline.Route(listOf(GROQ_STT, OPENAI_STT), listOf(GROQ_LLM, HAIKU, LUNA)) { keys[it.host] }
        val out = DictationPipeline(server.client(), Fallback { 0L }).run(route, UploadAudio(byteArrayOf(0), "a.flac", "audio/flac"), emptyList(), refine = true)
        assertEquals("Oi, tudo bem?", out.text)
        assertEquals("gpt-transcribe", out.sttModel)
        assertTrue(out.sttBackup)
        assertEquals("gpt-6-luna", out.refineModel) // Haiku skipped: no Anthropic key, no request sent
        assertTrue(out.refineBackup)
        val auth = server.sent.map { it.first.url.host to it.first.header("Authorization") }
        assertEquals(
            listOf("api.groq.com" to "Bearer gsk_1", "api.openai.com" to "Bearer sk-2", "api.groq.com" to "Bearer gsk_1", "api.openai.com" to "Bearer sk-2"),
            auth,
        )
    }

    @Test
    fun whenEveryRefinerFailsTheRawTextIsKept() {
        val server = FakeServer { req, _ ->
            if (req.url.encodedPath.endsWith("/audio/transcriptions")) Triple(200, """{"text":"texto cru"}""", emptyMap())
            else Triple(503, "{}", emptyMap())
        }
        val route = DictationPipeline.Route(listOf(GROQ_STT), listOf(GROQ_LLM, QWEN)) { "k" }
        val out = DictationPipeline(server.client(), Fallback { 0L }).run(route, UploadAudio(byteArrayOf(0), "a.flac", "audio/flac"), emptyList(), refine = true)
        assertEquals("texto cru", out.text)
        assertFalse(out.refined)
        assertNull(out.refineModel)
        assertTrue(out.refineError!!.contains("503"))
    }

    @Test
    fun noKeyAtAllReadsAsMissingKey() {
        val server = FakeServer { _, _ -> error("no request expected") }
        val route = DictationPipeline.Route(listOf(GROQ_STT), listOf(GROQ_LLM)) { null }
        try {
            DictationPipeline(server.client()).run(route, UploadAudio(byteArrayOf(0), "a.flac", "audio/flac"), emptyList(), refine = true)
            fail()
        } catch (e: ProviderException) {
            assertTrue(e.missingKey)
        }
        assertTrue(server.sent.isEmpty())
    }

    @Test
    fun warmingHitsEachMainHostOnceWithAHead() {
        val server = FakeServer { _, _ -> Triple(405, "", emptyMap()) }
        val route = DictationPipeline.Route(listOf(GROQ_STT, OPENAI_STT), listOf(GROQ_LLM, HAIKU)) { "k" }
        DictationPipeline(server.client()).warm(route)
        val sent = server.sent.map { it.first }
        assertEquals(1, sent.size)
        assertEquals("HEAD", sent[0].method)
        assertEquals("https://api.groq.com/openai/v1/models", sent[0].url.toString())
    }
}

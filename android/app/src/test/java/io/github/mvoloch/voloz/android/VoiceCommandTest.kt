package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.net.ApiClient
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.Fallback
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.net.UploadAudio
import io.github.mvoloch.voloz.android.refine.AppStyle
import io.github.mvoloch.voloz.android.refine.VoiceCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResource("refinement/$name")) { name }.readText()

    @Test
    fun instructionsMatchTheDesktopFile() {
        assertEquals(fixture("command_instructions.txt"), VoiceCommand.INSTRUCTIONS)
    }

    @Test
    fun promptAndMessageAreBuiltAsOnTheDesktop() {
        assertEquals(VoiceCommand.INSTRUCTIONS, VoiceCommand.prompt(null, tagged = false))
        assertEquals(
            VoiceCommand.INSTRUCTIONS + "\n\n" + AppStyle.EMAIL.instruction + "\n\n" + VoiceCommand.TAGGED_OUTPUT_INSTRUCTION,
            VoiceCommand.prompt(AppStyle.EMAIL, tagged = true),
        )
        assertEquals("<instruction>deixa mais formal</instruction>\n\n<selection>\noi, td bem?\n</selection>", VoiceCommand.message(" deixa mais formal ", "oi, td bem?"))
        assertEquals("<instruction>responde que aceito</instruction>\n\nNo text is selected.", VoiceCommand.message("responde que aceito", "  "))
        assertEquals("Prezado, tudo bem?", VoiceCommand.extractTagged("<result>Prezado, tudo bem?</result>\n\nNote: made it formal."))
    }

    private class Server(val stt: String, val reply: (Request, String) -> Pair<Int, String>) {
        val sent = mutableListOf<Pair<Request, String>>()
        fun client() = ApiClient(
            OkHttpClient.Builder().addInterceptor(
                Interceptor { chain ->
                    val req = chain.request()
                    val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
                    sent += req to body
                    val (code, text) = if (req.url.encodedPath.endsWith("/audio/transcriptions")) {
                        200 to """{"text":${JsonPrimitive(stt)}}"""
                    } else {
                        reply(req, body)
                    }
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                        .body(text.toResponseBody("application/json".toMediaType())).build()
                },
            ).build(),
        )
    }

    private fun chat(text: String) = """{"choices":[{"index":0,"message":{"role":"assistant","content":${JsonPrimitive(text)}},"finish_reason":"stop"}]}"""
    private val audio = UploadAudio(byteArrayOf(0), "a.flac", "audio/flac")
    private val groq = Provider(ProviderPresets.GROQ, "whisper-large-v3")
    private val oss = Provider(ProviderPresets.GROQ, "openai/gpt-oss-120b")
    private val haiku = Provider(ProviderPresets.ANTHROPIC, "claude-haiku-5-5")

    @Test
    fun aCommandRewritesTheSelectionAndFallsBackLikeRefinement() {
        val server = Server("deixa mais formal") { req, _ ->
            if (req.url.host == "api.groq.com") 503 to "{}"
            else 200 to """{"content":[{"type":"text","text":"<result>Prezado, tudo bem?</result>"}],"stop_reason":"end_turn"}"""
        }
        val route = DictationPipeline.Route(listOf(groq), listOf(oss, haiku)) { "k" }
        val out = DictationPipeline(server.client(), Fallback { 0L }).runCommand(route, audio, emptyList(), "oi, td bem?", AppStyle.EMAIL)
        assertEquals("Prezado, tudo bem?", out.text)
        assertEquals("claude-haiku-5-5", out.refineModel)
        assertEquals("deixa mais formal", out.raw)
        val claude = Json.parseToJsonElement(server.sent.last().second).jsonObject
        assertEquals((VoiceCommand.MAX_OUTPUT_TOKENS + 1024).toString(), claude["max_tokens"]!!.jsonPrimitive.content)
        assertTrue(claude["system"]!!.jsonPrimitive.content.contains("goes into an email"))
        assertTrue(claude["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content.contains("<selection>\noi, td bem?\n</selection>"))
    }

    @Test
    fun aDraftWithoutSelectionUsesTheOpenAiRules() {
        val server = Server("responde que eu aceito") { _, _ -> 200 to chat("Aceito, obrigado.") }
        val luna = Provider(ProviderPresets.OPENAI, "gpt-6-luna")
        val route = DictationPipeline.Route(listOf(groq), listOf(luna)) { "k" }
        val out = DictationPipeline(server.client(), Fallback { 0L }).runCommand(route, audio, emptyList(), "", AppStyle.CHAT)
        assertEquals("Aceito, obrigado", out.text) // chat: a short sentence loses its period
        val body = Json.parseToJsonElement(server.sent.last().second).jsonObject
        assertEquals("4096", body["max_completion_tokens"]!!.jsonPrimitive.content)
        assertEquals("0.3", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content.endsWith("No text is selected."))
    }

    @Test
    fun nothingHeardRunsNoCommand() {
        val server = Server("  ") { _, _ -> error("no command expected") }
        val route = DictationPipeline.Route(listOf(groq), listOf(oss)) { "k" }
        val out = DictationPipeline(server.client(), Fallback { 0L }).runCommand(route, audio, emptyList(), "texto", null)
        assertEquals("", out.text)
        assertNull(out.refineModel)
    }
}

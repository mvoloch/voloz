package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.net.ApiClient
import io.github.mvoloch.voloz.android.net.DictationCancelled
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.Fallback
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderException
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.net.UploadAudio
import io.github.mvoloch.voloz.android.ui.screens.distinctIds
import io.github.mvoloch.voloz.android.ui.screens.moveUp
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fixes from the 0.2.1 review: cancel, non-JSON replies, settings and history edge cases. */
class ReviewFixesTest {
    private val groq = Provider(ProviderPresets.GROQ, "whisper-large-v3")
    private val openai = Provider(ProviderPresets.OPENAI, "gpt-transcribe")
    private val oss = Provider(ProviderPresets.GROQ, "openai/gpt-oss-120b")
    private val luna = Provider(ProviderPresets.OPENAI, "gpt-6-luna")
    private val audio = UploadAudio(byteArrayOf(0), "a.flac", "audio/flac")

    private fun client(answer: (okhttp3.Request) -> Pair<Int, String>): Pair<ApiClient, MutableList<okhttp3.Request>> {
        val sent = mutableListOf<okhttp3.Request>()
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            sent += req
            val (code, body) = answer(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }
        return ApiClient(OkHttpClient.Builder().addInterceptor(interceptor).build()) to sent
    }

    @Test
    fun aStoppedRunTriesNoFurtherProvider() {
        var cancelled = false
        val tried = mutableListOf<Provider>()
        try {
            Fallback { 0L }.run("stt", listOf(groq, openai), stop = { cancelled }) { p ->
                tried += p
                cancelled = true // the user taps while the first request is in flight
                throw ProviderException(p, null, "Canceled")
            }
            fail()
        } catch (_: DictationCancelled) {
        }
        assertEquals(listOf(groq), tried)
    }

    @Test
    fun aCaptivePortalPageMovesOnToTheBackup() {
        val (api, sent) = client { req ->
            if (req.url.host == "api.groq.com") 200 to "<html>Login to the Wi-Fi</html>" else 200 to """{"text":"oi"}"""
        }
        val route = DictationPipeline.Route(listOf(groq, openai), emptyList()) { "k" }
        val out = DictationPipeline(api, Fallback { 0L }).run(route, audio, emptyList(), refine = false)
        assertEquals("oi", out.text)
        assertEquals("gpt-transcribe", out.sttModel)
        assertEquals(2, sent.size)
    }

    @Test
    fun cancellingDuringRefinementInsertsNothing() {
        lateinit var cancel: DictationPipeline.Cancel
        val (api, _) = client { req ->
            if (req.url.encodedPath.endsWith("/audio/transcriptions")) {
                200 to """{"text":"texto cru"}"""
            } else {
                cancel.cancel() // tapped while refining
                503 to "{}"
            }
        }
        val pipeline = DictationPipeline(api, Fallback { 0L })
        cancel = pipeline.newCancel()
        val route = DictationPipeline.Route(listOf(groq), listOf(oss, luna)) { "k" }
        try {
            pipeline.run(route, audio, emptyList(), refine = true, cancel = cancel)
            fail("a cancelled dictation must not fall back to the raw text")
        } catch (_: DictationCancelled) {
        }
    }

    @Test
    fun historyKeysAreUniqueAndStable() {
        assertEquals(listOf(5L, 6L, 7L, 3L), distinctIds(listOf(5L, 5L, 5L, 3L)))
        // A new dictation on top doesn't change the older rows' keys.
        assertEquals(listOf(9L, 5L, 3L), distinctIds(listOf(9L, 5L, 3L)))
    }

    @Test
    fun movingUpUsesTheProviderNotAStaleIndex() {
        assertEquals(listOf(luna, oss), moveUp(listOf(oss, luna), luna))
        assertEquals(listOf(oss, luna), moveUp(listOf(oss, luna), oss))
        val gone = Provider("https://x.example/v1", "m")
        assertEquals(listOf(oss, luna), moveUp(listOf(oss, luna), gone))
        assertTrue(oss.sameAs(Provider(" https://api.groq.com/openai/v1/ ", "openai/gpt-oss-120b")))
    }

    @Test
    fun aLockedLanguageIsSentAndAutoIsLeftOut() {
        val bodies = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            bodies += okio.Buffer().also { req.body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("x")
                .body("""{"text":"oi"}""".toResponseBody("application/json".toMediaType())).build()
        }
        val api = ApiClient(OkHttpClient.Builder().addInterceptor(interceptor).build())
        api.transcribe(groq, "k", audio, null, "pt")
        api.transcribe(groq, "k", audio, null, null)
        assertTrue(bodies[0].contains("name=\"language\"") && bodies[0].contains("\r\n\r\npt\r\n"))
        assertTrue(!bodies[1].contains("name=\"language\""))
    }
}

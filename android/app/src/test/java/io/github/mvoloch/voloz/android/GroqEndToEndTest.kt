package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.audio.WavWriter
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.GroqClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real network check of the app's own Groq pipeline code. Skipped unless
 * GROQ_API_KEY and VOLOZ_E2E_WAV are set (VOLOZ_E2E_OUT = where to write the
 * JSON report). The key is never printed.
 */
class GroqEndToEndTest {
    @Test
    fun transcribeAndRefineFixture() {
        val key = System.getenv("GROQ_API_KEY").orEmpty()
        val wavPath = System.getenv("VOLOZ_E2E_WAV").orEmpty()
        assumeTrue("GROQ_API_KEY / VOLOZ_E2E_WAV not set", key.isNotBlank() && wavPath.isNotBlank())

        // Re-wrap the fixture's PCM with the app's WavWriter so the upload is byte-for-byte what the app sends.
        val file = File(wavPath).readBytes()
        val bb = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = bb.getInt(24)
        val channels = bb.getShort(22).toInt()
        val bits = bb.getShort(34).toInt()
        check(channels == 1 && bits == 16) { "fixture must be mono PCM16" }
        // Walk the RIFF chunks to the "data" chunk (the header may carry LIST/fact chunks).
        var offset = 12
        var pcm: ByteArray? = null
        while (offset + 8 <= file.size) {
            val id = String(file, offset, 4, Charsets.US_ASCII)
            val size = bb.getInt(offset + 4)
            if (id == "data") {
                pcm = file.copyOfRange(offset + 8, minOf(file.size, offset + 8 + size))
                break
            }
            offset += 8 + size + (size and 1)
        }
        val audio = checkNotNull(pcm) { "no data chunk" }
        val wav = WavWriter.pcm16ToWav(audio, sampleRate)

        val groq = GroqClient()
        val vocabulary = listOf("Voloz", "Groq")
        val t0 = System.nanoTime()
        val models = groq.testKey(key)
        val testKeyMs = (System.nanoTime() - t0) / 1_000_000
        val out = DictationPipeline(groq).run(key, wav, vocabulary, refine = true)

        val report = buildJsonObject {
            put("audio_seconds", audio.size / 2.0 / sampleRate)
            put("upload_bytes", wav.size)
            put("models_visible", models)
            put("test_key_ms", testKeyMs)
            put("stt_ms", out.sttMs)
            put("refine_ms", out.refineMs)
            put("refined", out.refined)
            put("refine_error", out.refineError ?: "")
            put("raw", out.raw)
            put("text", out.text)
        }.toString()
        System.getenv("VOLOZ_E2E_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(report) }
        println(report)
        check(out.raw.isNotBlank()) { "empty transcript" }
        check(out.refined) { "refinement failed: ${out.refineError}" }
    }
}

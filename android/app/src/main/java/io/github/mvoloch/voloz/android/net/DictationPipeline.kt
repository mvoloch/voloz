package io.github.mvoloch.voloz.android.net

import io.github.mvoloch.voloz.android.audio.FlacEncoder
import io.github.mvoloch.voloz.android.audio.WavWriter
import io.github.mvoloch.voloz.android.refine.RefinementPrompt

/**
 * Audio -> text: transcription, then (optionally) refinement, each on the main
 * provider and then its backups (see [Fallback]). A refinement failure falls
 * back to the raw transcript. Pure JVM so the e2e test runs it as is.
 */
class DictationPipeline(private val client: ApiClient, private val fallback: Fallback = Fallback()) {

    data class Output(
        val raw: String,
        val text: String,
        val refined: Boolean,
        val refineError: String?,
        val sttMs: Long,
        val refineMs: Long,
        /** The model that transcribed and the one that refined (null when not refined). */
        val sttModel: String,
        val refineModel: String?,
        /** True when a backup provider answered instead of the main one. */
        val sttBackup: Boolean,
        val refineBackup: Boolean,
    )

    /** What a dictation runs on: providers in order (main first) and the key for each host. */
    class Route(
        val stt: List<Provider>,
        val refine: List<Provider>,
        val keyFor: (Provider) -> String?,
    )

    companion object {
        /** 16-bit mono FLAC, or the WAV when encoding fails (it never should) or doesn't shrink it. */
        fun uploadAudio(pcm: ByteArray, sampleRate: Int): UploadAudio {
            val flac = runCatching { FlacEncoder.encode(pcm, sampleRate) }.getOrNull()
            if (flac != null && flac.size < pcm.size + WavWriter.HEADER_BYTES) {
                return UploadAudio(flac, "audio.flac", "audio/flac")
            }
            return UploadAudio(WavWriter.pcm16ToWav(pcm, sampleRate), "audio.wav", "audio/wav")
        }
    }

    /** Opens the main providers' connections while the user speaks (best effort, blocking: call off the main thread). */
    fun warm(route: Route) {
        val firsts = listOfNotNull(route.stt.firstOrNull(), route.refine.firstOrNull())
        for (endpoint in firsts.map { it.endpoint.trim().trimEnd('/') }.distinct()) {
            val p = firsts.first { it.endpoint.trim().trimEnd('/') == endpoint }
            client.warm(p.endpoint, route.keyFor(p))
        }
    }

    fun run(route: Route, audio: UploadAudio, vocabulary: List<String>, refine: Boolean): Output {
        val t0 = System.nanoTime()
        val stt = fallback.run("stt", route.stt) { p ->
            client.transcribe(p, keyOrThrow(route, p), audio, RefinementPrompt.buildSttPrompt(vocabulary))
        }
        val raw = stt.value.trim()
        val sttMs = (System.nanoTime() - t0) / 1_000_000
        fun output(text: String, refined: Answer?, error: String?, refineMs: Long) = Output(
            raw = raw, text = text, refined = refined != null, refineError = error, sttMs = sttMs, refineMs = refineMs,
            sttModel = stt.provider.model, refineModel = refined?.provider?.model,
            sttBackup = stt.backup, refineBackup = refined?.backup ?: false,
        )
        if (raw.isEmpty() || !refine || route.refine.isEmpty()) return output(raw, null, null, 0)

        // Same pre-pass as the desktop: the LLM never sees Whisper loops.
        val cleaned = RefinementPrompt.collapseRepetitiveArtifacts(raw)
        if (cleaned.isBlank()) return output("", null, null, 0)

        val t1 = System.nanoTime()
        return try {
            val answer = fallback.run("llm", route.refine) { p -> client.refine(p, keyOrThrow(route, p), cleaned, vocabulary) }
            output(answer.value, answer, null, (System.nanoTime() - t1) / 1_000_000)
        } catch (e: Exception) {
            output(raw, null, e.message ?: e.javaClass.simpleName, (System.nanoTime() - t1) / 1_000_000)
        }
    }

    private fun keyOrThrow(route: Route, p: Provider): String =
        route.keyFor(p)?.takeIf { it.isNotBlank() } ?: throw ProviderException(p, null, null, missingKey = true)

    /** Forget rate-limit cooldowns (the user changed providers or keys). */
    fun resetCooldowns() = fallback.reset()
}

private typealias Answer = Fallback.Answer<String>

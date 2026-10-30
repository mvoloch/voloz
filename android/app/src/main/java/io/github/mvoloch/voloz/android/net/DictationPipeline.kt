package io.github.mvoloch.voloz.android.net

import io.github.mvoloch.voloz.android.refine.RefinementPrompt

/**
 * Audio -> text: Groq Whisper, then (optionally) Groq refinement. A refinement
 * failure falls back to the raw transcript. Pure JVM so the e2e test runs it as is.
 */
class DictationPipeline(private val groq: GroqClient) {

    data class Output(
        val raw: String,
        val text: String,
        val refined: Boolean,
        val refineError: String?,
        val sttMs: Long,
        val refineMs: Long,
    )

    fun run(apiKey: String, wav: ByteArray, vocabulary: List<String>, refine: Boolean): Output {
        val t0 = System.nanoTime()
        val raw = groq.transcribe(apiKey, wav, RefinementPrompt.buildSttPrompt(vocabulary)).trim()
        val sttMs = (System.nanoTime() - t0) / 1_000_000
        if (raw.isEmpty() || !refine) return Output(raw, raw, false, null, sttMs, 0)

        // Same pre-pass as the desktop: the LLM never sees Whisper loops.
        val cleaned = RefinementPrompt.collapseRepetitiveArtifacts(raw)
        if (cleaned.isBlank()) return Output(raw, "", false, null, sttMs, 0)

        val t1 = System.nanoTime()
        return try {
            val text = groq.refine(apiKey, cleaned, vocabulary)
            Output(raw, text, true, null, sttMs, (System.nanoTime() - t1) / 1_000_000)
        } catch (e: Exception) {
            Output(raw, raw, false, e.message ?: e.javaClass.simpleName, sttMs, (System.nanoTime() - t1) / 1_000_000)
        }
    }
}

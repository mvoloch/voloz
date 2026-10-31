package io.github.mvoloch.voloz.android.net

import io.github.mvoloch.voloz.android.audio.FlacEncoder
import io.github.mvoloch.voloz.android.audio.WavWriter
import io.github.mvoloch.voloz.android.refine.PromptOptions
import io.github.mvoloch.voloz.android.refine.RefineFlags
import io.github.mvoloch.voloz.android.refine.RefinementPrompt
import io.github.mvoloch.voloz.android.text.Replacement
import io.github.mvoloch.voloz.android.text.Replacements
import io.github.mvoloch.voloz.android.text.Snippet
import io.github.mvoloch.voloz.android.text.Snippets

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
        /** The text came from a text shortcut (a whole-dictation trigger, or a refinement that lost one). */
        val shortcut: Boolean = false,
    )

    /** The user's replacement rules and text shortcuts (desktop vocabulary.py / snippets.py). */
    class TextRules(val replacements: List<Replacement> = emptyList(), val snippets: List<Snippet> = emptyList())

    /** Stops a run: no further provider is tried and the request in flight is aborted. */
    class Cancel(private val abort: () -> Unit) {
        @Volatile var cancelled = false
            private set

        fun cancel() {
            cancelled = true
            abort()
        }
    }

    /** A cancel signal for one run; cancelling also aborts the client's requests. */
    fun newCancel() = Cancel { client.cancelAll() }

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
        listOfNotNull(route.stt.firstOrNull(), route.refine.firstOrNull())
            .distinctBy { it.baseUrl }
            .forEach { client.warm(it.endpoint, route.keyFor(it)) }
    }

    /**
     * Transcribe, apply the replacement rules, then: a dictation that is only a shortcut
     * trigger becomes its text with no refinement; otherwise triggers go through
     * refinement as placeholders and come back as their texts (unrefined text with the
     * shortcuts expanded when the model loses one, or when refinement is off or fails).
     * Throws [DictationCancelled] once [cancel] fires, also mid-refinement (no raw text then).
     */
    fun run(
        route: Route,
        audio: UploadAudio,
        vocabulary: List<String>,
        refine: Boolean,
        cancel: Cancel = Cancel {},
        rules: TextRules = TextRules(),
        flags: RefineFlags = RefineFlags(),
        /** ISO-639-1 code, or null to auto-detect. */
        language: String? = null,
        /** Called (on the calling thread) once the transcript is in, before refinement. */
        onTranscribed: () -> Unit = {},
    ): Output {
        val t0 = System.nanoTime()
        val stop = { cancel.cancelled }
        val stt = fallback.run("stt", route.stt, stop) { p ->
            client.transcribe(p, keyOrThrow(route, p), audio, RefinementPrompt.buildSttPrompt(vocabulary), language)
        }
        val raw = Replacements.apply(stt.value.trim(), rules.replacements)
        val sttMs = (System.nanoTime() - t0) / 1_000_000
        onTranscribed()
        val r = finish(route, raw, vocabulary, refine, cancel, rules, flags)
        return Output(
            raw = raw, text = r.text, refined = r.answer != null, refineError = r.error, sttMs = sttMs, refineMs = r.ms,
            sttModel = stt.provider.model, refineModel = r.answer?.provider?.model,
            sttBackup = stt.backup, refineBackup = r.answer?.backup ?: false, shortcut = r.shortcut,
        )
    }

    /** What the text became after the transcript: shortcuts and refinement (or why it wasn't refined). */
    class Finished(val text: String, val answer: Answer?, val error: String?, val ms: Long, val shortcut: Boolean)

    /**
     * History "Refinar de novo": the stored raw transcript through the same path as a new
     * dictation (shortcuts, placeholders, providers with backups). [Finished.error] says why
     * the text came back unrefined.
     */
    fun refineAgain(route: Route, raw: String, vocabulary: List<String>, rules: TextRules, flags: RefineFlags = RefineFlags()): Finished =
        finish(route, raw.trim(), vocabulary, refine = true, cancel = Cancel {}, rules = rules, flags = flags)

    private fun finish(
        route: Route,
        raw: String,
        vocabulary: List<String>,
        refine: Boolean,
        cancel: Cancel,
        rules: TextRules,
        flags: RefineFlags,
    ): Finished {
        if (raw.isEmpty()) return Finished(raw, null, null, 0, false)
        Snippets.matchWhole(raw, rules.snippets)?.let { return Finished(it, null, null, 0, shortcut = true) }
        val unrefined = Snippets.expand(raw, rules.snippets)
        if (!refine || route.refine.isEmpty()) return Finished(unrefined, null, null, 0, false)

        val (protected, tokens) = Snippets.protect(raw, rules.snippets)
        // Same pre-pass as the desktop: the LLM never sees Whisper loops.
        val cleaned = RefinementPrompt.collapseRepetitiveArtifacts(protected)
        if (cleaned.isBlank()) return Finished("", null, null, 0, false)

        val t1 = System.nanoTime()
        fun ms() = (System.nanoTime() - t1) / 1_000_000
        return try {
            val answer = fallback.run("llm", route.refine, { cancel.cancelled }) { p ->
                client.refine(p, keyOrThrow(route, p), cleaned, PromptOptions(vocabulary, flags, tokens.keys.toList()))
            }
            val restored = Snippets.restore(answer.value, tokens)
            // A lost or repeated placeholder: guessing where a shortcut belonged could put it
            // in the wrong place; the unrefined text has each one where it was said.
            if (restored == null) Finished(unrefined, null, "refinement lost a text-shortcut placeholder", ms(), shortcut = true)
            else Finished(restored, answer, null, ms(), false)
        } catch (e: DictationCancelled) {
            throw e
        } catch (e: Exception) {
            if (cancel.cancelled) throw DictationCancelled()
            Finished(unrefined, null, e.message ?: e.javaClass.simpleName, ms(), false)
        }
    }

    private fun keyOrThrow(route: Route, p: Provider): String =
        route.keyFor(p)?.takeIf { it.isNotBlank() } ?: throw ProviderException(p, null, null, missingKey = true)

    /** Forget rate-limit cooldowns (the user changed providers or keys). */
    fun resetCooldowns() = fallback.reset()
}

typealias Answer = Fallback.Answer<String>

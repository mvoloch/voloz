package io.github.mvoloch.voloz.android.refine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Port of the desktop app's refinement prompt assembly
 * (backend/services/refinement.py `build_refinement_prompt` with every flag on,
 * plus `_KEEP_LANGUAGE_INSTRUCTION` as used for non-Claude backends) and of
 * backend/services/vocabulary.py `build_stt_prompt`.
 */
/** Desktop `RefinementFlags`: which refinement behaviours apply (all on by default). */
data class RefineFlags(
    val smartCleanup: Boolean = true,
    val selfCorrection: Boolean = true,
    val preserveTechnical: Boolean = true,
)

/** Everything the refinement prompt depends on besides the transcript. */
data class PromptOptions(
    val vocabulary: List<String> = emptyList(),
    val flags: RefineFlags = RefineFlags(),
    /** Text-shortcut placeholders in the transcript (see text/Snippets.kt). */
    val placeholders: List<String> = emptyList(),
)

object RefinementPrompt {
    /** vocabulary.py STT_PROMPT_MAX_CHARS: keeps Whisper's prompt under its 224-token window. */
    const val STT_PROMPT_MAX_CHARS = 400
    const val MAX_VOCABULARY_TERMS = 200
    const val MAX_TERM_CHARS = 100
    private const val REPETITION_RUN_THRESHOLD = 6
    private const val MAX_REPETITION_UNIT_CHARS = 60

    // Unicode-aware like Python's re (the desktop's), written with explicit property
    // classes. Do not use the (?U) flag: the JVM accepts it, but Android's ICU regex
    // throws PatternSyntaxException, which broke every dictation on device.
    internal const val WHITESPACE_PATTERN = "[\\s\\p{Z}\\x{85}]+"
    internal const val NON_WORD_PATTERN = "[^\\p{L}\\p{M}\\p{Nd}\\p{Pc}\\x{200C}\\x{200D}]"
    private val WHITESPACE = Regex(WHITESPACE_PATTERN)
    private val NON_WORD = Regex(NON_WORD_PATTERN)

    /**
     * refinement.py `_TRANSCRIPT_TAG` / `_TAGGED_OUTPUT_INSTRUCTION`: Claude models get the
     * transcript back inside tags and only the tagged text is kept (they sometimes add a
     * note about their edits after the text). Hand-copied: gen_prompt.py skips f-strings.
     */
    const val TRANSCRIPT_TAG = "transcript"
    const val TAGGED_OUTPUT_INSTRUCTION =
        "Return the cleaned transcript inside <$TRANSCRIPT_TAG></$TRANSCRIPT_TAG> tags and nothing outside them."
    private val TAGGED = Regex("<$TRANSCRIPT_TAG>(.*?)</$TRANSCRIPT_TAG>", RegexOption.DOT_MATCHES_ALL)

    /**
     * System prompt: base + smart cleanup + spelling + self-correction + technical (+ vocabulary),
     * then the keep-language rule, or for Claude ([tagged]) the tagged-output rule.
     */
    fun buildSystemPrompt(vocabulary: List<String>, tagged: Boolean = false, placeholders: List<String> = emptyList()): String =
        buildSystemPrompt(PromptOptions(vocabulary, placeholders = placeholders), tagged)

    /**
     * build_refinement_prompt for [opts]'s flags: cleanup (+ spelling), self-correction,
     * technical terms; with every toggle off, the desktop's pass-through line and no
     * vocabulary. Then the placeholder line and the keep-language / tagged-output rule.
     */
    fun buildSystemPrompt(opts: PromptOptions, tagged: Boolean = false): String {
        val sections = mutableListOf(RefinementPromptText.BASE_INSTRUCTIONS)
        if (opts.flags.smartCleanup) {
            sections += RefinementPromptText.SMART_CLEANUP
            sections += RefinementPromptText.SPELLING
        }
        if (opts.flags.selfCorrection) sections += RefinementPromptText.SELF_CORRECTION
        if (opts.flags.preserveTechnical) sections += RefinementPromptText.PRESERVE_TECHNICAL
        if (sections.size == 1) {
            sections += PASS_THROUGH
        } else {
            val terms = opts.vocabulary.map { it.trim() }.filter { it.isNotEmpty() }
            if (terms.isNotEmpty()) {
                sections += RefinementPromptText.VOCABULARY_TEMPLATE.replace("{terms}", terms.joinToString(", "))
            }
        }
        if (opts.placeholders.isNotEmpty()) {
            sections += PLACEHOLDER_INSTRUCTION.replace("{tokens}", opts.placeholders.joinToString(", "))
        }
        val last = if (tagged) TAGGED_OUTPUT_INSTRUCTION else RefinementPromptText.KEEP_LANGUAGE
        return sections.joinToString("\n\n") + "\n\n" + last
    }

    /**
     * refinement.py `_PLACEHOLDER_INSTRUCTION`, added only when the transcript holds text-shortcut
     * placeholders (see text/Snippets.kt), so other prompts stay as they were. Hand-copied.
     */
    /** build_refinement_prompt's line when every toggle is off (hand-copied; inline in the desktop function). */
    const val PASS_THROUGH = "No transformations are enabled. Return the transcript unchanged."

    const val PLACEHOLDER_INSTRUCTION =
        "Copy each of these placeholders exactly once, unchanged, where it stands in the sentence: {tokens}. Each stands for text inserted later."

    /** The few-shot pairs with each answer inside the transcript tags (Claude). */
    fun taggedExamples(): List<Pair<String, String>> =
        RefinementPromptText.EXAMPLES.map { (user, assistant) -> user to "<$TRANSCRIPT_TAG>$assistant</$TRANSCRIPT_TAG>" }

    /** The text inside the transcript tags, or [text] when there are none (refinement.py `extract_tagged_transcript`). */
    fun extractTagged(text: String): String = TAGGED.find(text)?.groupValues?.get(1)?.trim() ?: text

    /** Chat messages: system, the few-shot pairs as real turns, then the transcript. */
    fun buildMessages(transcript: String, vocabulary: List<String>, placeholders: List<String> = emptyList()): JsonArray =
        buildMessages(transcript, PromptOptions(vocabulary, placeholders = placeholders))

    fun buildMessages(transcript: String, opts: PromptOptions): JsonArray = buildJsonArray {
        add(message("system", buildSystemPrompt(opts)))
        for ((user, assistant) in RefinementPromptText.EXAMPLES) {
            add(message("user", user))
            add(message("assistant", assistant))
        }
        add(message("user", transcript))
    }

    private fun message(role: String, content: String) =
        JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive(content)))

    private fun cleanTerm(term: String): String = term.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    /** Trim, collapse inner whitespace, drop empties/over-long terms, dedupe case-insensitively, cap the count. */
    fun normalizeVocabulary(terms: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in terms) {
            val term = cleanTerm(raw)
            if (term.isEmpty() || term.length > MAX_TERM_CHARS) continue
            if (!seen.add(term.lowercase())) continue
            out += term
            if (out.size == MAX_VOCABULARY_TERMS) break
        }
        return out
    }

    /** Whisper `prompt`: whole terms, comma-separated, until [STT_PROMPT_MAX_CHARS]; null when empty. */
    fun buildSttPrompt(vocabulary: List<String>): String? {
        val parts = ArrayList<String>()
        var length = 0
        for (raw in vocabulary) {
            val term = cleanTerm(raw)
            if (term.isEmpty()) continue
            val added = term.length + if (parts.isEmpty()) 0 else 2
            if (length + added > STT_PROMPT_MAX_CHARS) break
            parts += term
            length += added
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /** refinement.py `collapse_repetitive_artifacts`: strip Whisper loops before the LLM sees them. */
    fun collapseRepetitiveArtifacts(text: String, minRun: Int = REPETITION_RUN_THRESHOLD): String =
        collapseCharacterRuns(collapseWordRuns(text, minRun), minRun)

    private fun tokenKey(word: String): String = NON_WORD.replace(word, "").lowercase()

    private fun collapseWordRuns(text: String, minRun: Int): String {
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < minRun) return text
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val key = tokenKey(words[i])
            var j = i
            if (key.isNotEmpty()) {
                while (j < words.size && tokenKey(words[j]) == key) j++
            } else {
                j = i + 1
            }
            if (j - i < minRun) out.addAll(words.subList(i, j))
            i = j
        }
        return out.joinToString(" ")
    }

    private fun collapseCharacterRuns(text: String, minRun: Int): String {
        val pattern = Regex("(.{2,$MAX_REPETITION_UNIT_CHARS}?)\\1{${minRun - 1},}", RegexOption.DOT_MATCHES_ALL)
        val result = pattern.replace(text, "")
        if (result == text) return text
        return WHITESPACE.replace(result, " ").trim()
    }
}

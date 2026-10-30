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
object RefinementPrompt {
    /** vocabulary.py STT_PROMPT_MAX_CHARS: keeps Whisper's prompt under its 224-token window. */
    const val STT_PROMPT_MAX_CHARS = 400
    const val MAX_VOCABULARY_TERMS = 200
    const val MAX_TERM_CHARS = 100
    private const val REPETITION_RUN_THRESHOLD = 6
    private const val MAX_REPETITION_UNIT_CHARS = 60

    private val WHITESPACE = Regex("(?U)\\s+")
    private val NON_WORD = Regex("(?U)[^\\w]")

    /** System prompt: base + smart cleanup + spelling + self-correction + technical (+ vocabulary) + keep-language. */
    fun buildSystemPrompt(vocabulary: List<String>): String {
        val sections = mutableListOf(
            RefinementPromptText.BASE_INSTRUCTIONS,
            RefinementPromptText.SMART_CLEANUP,
            RefinementPromptText.SPELLING,
            RefinementPromptText.SELF_CORRECTION,
            RefinementPromptText.PRESERVE_TECHNICAL,
        )
        val terms = vocabulary.map { it.trim() }.filter { it.isNotEmpty() }
        if (terms.isNotEmpty()) {
            sections += RefinementPromptText.VOCABULARY_TEMPLATE.replace("{terms}", terms.joinToString(", "))
        }
        return sections.joinToString("\n\n") + "\n\n" + RefinementPromptText.KEEP_LANGUAGE
    }

    /** Chat messages: system, the few-shot pairs as real turns, then the transcript. */
    fun buildMessages(transcript: String, vocabulary: List<String>): JsonArray = buildJsonArray {
        add(message("system", buildSystemPrompt(vocabulary)))
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

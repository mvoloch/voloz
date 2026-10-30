package io.github.mvoloch.voloz.android.text

import java.text.Normalizer

/** "Quando eu disser" -> "colar este texto": a short phrase that becomes a saved text. */
data class Snippet(val trigger: String, val text: String)

/**
 * Port of the desktop's text shortcuts (backend/services/snippets.py). A dictation
 * that is only a trigger (fillers around it allowed) becomes the text without
 * refinement; inside a longer dictation each trigger goes through refinement as a
 * placeholder the model must keep, and is put back afterwards. Matching ignores
 * case, accents and punctuation.
 */
object Snippets {
    const val MAX_SNIPPETS = 100
    const val MAX_TRIGGER_CHARS = 100
    const val MAX_TEXT_CHARS = 4000
    private const val PLACEHOLDER_FORMAT = "[[S%d]]"
    private val PLACEHOLDER = Regex("\\[\\[S\\d+]]")
    private val WORD_RUN = Regex("${Replacements.WORD}+")
    private val COMBINING = Regex("\\p{Mn}+")

    /** Words allowed around a trigger when the whole dictation is the shortcut (compared folded). */
    private val EDGE_FILLERS = setOf(
        // English
        "um", "umm", "uh", "uhm", "er", "erm", "ah", "oh", "hm", "hmm", "so", "ok", "okay",
        // Portuguese
        "e", "eh", "ahn", "hum", "humm", "tipo", "entao", "bom", "ne", "ai",
    )

    /** Case- and accent-insensitive form: NFKD, combining marks dropped, lowercased ("ß" -> "ss"). */
    internal fun fold(text: String): String =
        COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase().replace("ß", "ss")

    /** [fold] one character at a time, with where each folded character came from. */
    private fun foldWithIndex(text: String): Pair<String, IntArray> {
        val folded = StringBuilder()
        val origin = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            val piece = fold(text.substring(i, i + width))
            folded.append(piece)
            repeat(piece.length) { origin += i }
            i += width
        }
        return folded.toString() to origin.toIntArray()
    }

    private fun triggerWords(trigger: String): List<String> = WORD_RUN.findAll(fold(trigger)).map { it.value }.toList()

    private fun cleanText(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** Trimmed shortcuts with a usable trigger and text; a later trigger (folded) wins, in the later place. */
    fun normalize(snippets: List<Snippet>): List<Snippet> {
        val byKey = LinkedHashMap<String, Snippet>()
        for (s in snippets) {
            val trigger = s.trigger.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            val text = cleanText(s.text)
            val key = triggerWords(trigger).joinToString(" ")
            if (key.isEmpty() || text.isEmpty() || trigger.length > MAX_TRIGGER_CHARS || text.length > MAX_TEXT_CHARS) continue
            byKey.remove(key)
            byKey[key] = Snippet(trigger, text)
        }
        return byKey.values.toList().takeLast(MAX_SNIPPETS)
    }

    /** The shortcut text when [transcript] is just a trigger (fillers around it allowed); the longer trigger wins. */
    fun matchWhole(transcript: String, snippets: List<Snippet>): String? {
        if (transcript.isBlank()) return null
        val words = WORD_RUN.findAll(fold(transcript)).map { it.value }.toList()
        if (words.isEmpty()) return null
        var best: Pair<Int, String>? = null
        for (s in normalize(snippets)) {
            val trigger = triggerWords(s.trigger)
            val n = trigger.size
            for (start in 0..words.size - n) {
                if (words.subList(start, start + n) != trigger) continue
                val around = words.subList(0, start) + words.subList(start + n, words.size)
                if (around.all { it in EDGE_FILLERS } && (best == null || n > best.first)) best = n to s.text
                break
            }
        }
        return best?.second
    }

    /** Every trigger inside [text] replaced by its shortcut text, in one pass. */
    fun expand(text: String, snippets: List<Snippet>): String = replaceTriggers(text, snippets) { it }

    /** Every trigger replaced by its own placeholder ([[S1]], [[S2]]…), and the map back to the texts. */
    fun protect(text: String, snippets: List<Snippet>): Pair<String, Map<String, String>> {
        val tokens = LinkedHashMap<String, String>()
        val out = replaceTriggers(text, snippets) { expansion ->
            PLACEHOLDER_FORMAT.format(tokens.size + 1).also { tokens[it] = expansion }
        }
        return out to tokens
    }

    /** Each placeholder back to its text; null when one is missing, repeated or unknown. */
    fun restore(text: String, tokens: Map<String, String>): String? {
        val found = PLACEHOLDER.findAll(text).map { it.value }.toList()
        if (found.sorted() != tokens.keys.sorted()) return null
        return PLACEHOLDER.replace(text) { tokens.getValue(it.value) }
    }

    private fun replaceTriggers(text: String, snippets: List<Snippet>, replace: (String) -> String): String {
        if (text.isEmpty()) return text
        val cleaned = normalize(snippets).sortedByDescending { triggerWords(it.trigger).size }
        if (cleaned.isEmpty()) return text
        val nonWord = "[^\\p{L}\\p{M}\\p{Nd}\\p{Pc}]+"
        val pattern = Regex(
            cleaned.joinToString("|") { s ->
                "((?<!${Replacements.WORD})" + triggerWords(s.trigger).joinToString(nonWord) { Regex.escape(it) } + "(?!${Replacements.WORD}))"
            },
        )
        val (folded, origin) = foldWithIndex(text)
        val out = StringBuilder()
        var last = 0
        for (m in pattern.findAll(folded)) {
            val start = origin[m.range.first]
            val end = origin[m.range.last] + 1
            if (start < last) continue
            val which = (1..cleaned.size).first { m.groups[it] != null } - 1
            out.append(text, last, start).append(replace(cleaned[which].text))
            last = end
        }
        if (out.isEmpty() && last == 0) return text
        return out.append(text, last, text.length).toString()
    }
}

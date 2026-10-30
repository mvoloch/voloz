package io.github.mvoloch.voloz.android.text

import java.util.regex.Pattern

/** "Ouvido como" -> "escrever como": a word or phrase the transcription always gets wrong. */
data class Replacement(val from: String, val to: String)

/**
 * Port of the desktop's replacement rules (backend/services/vocabulary.py
 * `normalize_replacements`, `apply_replacements`, `suggest_replacements`).
 * Applied to the raw transcript, before refinement and also with refinement off.
 */
object Replacements {
    const val MAX_RULES = 200
    const val MAX_SIDE_CHARS = 100
    private const val MIN_EDIT_SIMILARITY = 0.5
    private const val MAX_SUGGESTION_WORDS = 3
    private const val MAX_SUGGESTIONS = 5

    // Unicode-aware like Python's re, with explicit classes (Android's ICU rejects (?U)).
    internal const val WORD = "[\\p{L}\\p{M}\\p{Nd}\\p{Pc}]"
    private val WHITESPACE = Regex("[\\s\\p{Z}\\x{85}]+")
    private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{M}\\p{Nd}\\p{Pc}]+|[^\\p{L}\\p{M}\\p{Nd}\\p{Pc}]+$")
    private val STARTS_WITH_WORD = Regex("^$WORD")
    private val ENDS_WITH_WORD = Regex("$WORD$")

    private fun clean(term: String): String = term.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private fun key(term: String): String = term.lowercase()

    /**
     * Trimmed, deduplicated rules with both sides non-empty and different. Two rules
     * for the same "from" (case-insensitive) keep the later one, in the later place.
     * Rules past the caps are dropped (the UI refuses them first).
     */
    fun normalize(rules: List<Replacement>): List<Replacement> {
        val byKey = LinkedHashMap<String, Replacement>()
        for (r in rules) {
            val from = clean(r.from)
            val to = clean(r.to)
            if (from.isEmpty() || to.isEmpty() || from == to) continue
            if (from.length > MAX_SIDE_CHARS || to.length > MAX_SIDE_CHARS) continue
            byKey.remove(key(from))
            byKey[key(from)] = Replacement(from, to)
        }
        return byKey.values.toList().takeLast(MAX_RULES)
    }

    /** Regex for [phrase] as a whole word or phrase, any whitespace between its words. */
    private fun phrasePattern(phrase: String): String {
        val body = phrase.split(" ").joinToString("[\\s\\p{Z}\\x{85}]+") { Pattern.quote(it) }
        val prefix = if (STARTS_WITH_WORD.containsMatchIn(phrase)) "(?<!$WORD)" else ""
        val suffix = if (ENDS_WITH_WORD.containsMatchIn(phrase)) "(?!$WORD)" else ""
        return prefix + body + suffix
    }

    /**
     * All rules in one pass, case-insensitive, whole words or phrases; the "to" side is
     * written exactly as given. One rule's output is never rewritten by another, and
     * longer phrases win over the words inside them.
     */
    fun apply(text: String, rules: List<Replacement>): String {
        if (text.isEmpty()) return text
        val cleaned = normalize(rules).sortedByDescending { it.from.length }
        if (cleaned.isEmpty()) return text
        val pattern = Pattern.compile(
            cleaned.indices.joinToString("|") { "(${phrasePattern(cleaned[it].from)})" },
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )
        val m = pattern.matcher(text)
        val out = StringBuilder()
        var last = 0
        while (m.find()) {
            val rule = (1..cleaned.size).first { m.group(it) != null } - 1
            out.append(text, last, m.start()).append(cleaned[rule].to)
            last = m.end()
        }
        if (last == 0 && out.isEmpty()) return text
        return out.append(text, last, text.length).toString()
    }

    private fun stripEdges(word: String) = EDGE_PUNCTUATION.replace(word, "")

    /**
     * Short word substitutions that turn [before] into [after] (a hand correction):
     * each place where 1-3 words were replaced by 1-3 other words, case and edge
     * punctuation ignored. Insertions, deletions, case-only changes and rewrites of
     * most of the text suggest nothing.
     */
    fun suggest(before: String, after: String): List<Replacement> {
        val a = before.split(WHITESPACE).map(::stripEdges).filter { it.isNotEmpty() }
        val b = after.split(WHITESPACE).map(::stripEdges).filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val matcher = SequenceMatcher(a.map(::key), b.map(::key))
        if (matcher.ratio() < MIN_EDIT_SIMILARITY) return emptyList()
        val out = ArrayList<Replacement>()
        val seen = HashSet<Pair<String, String>>()
        for (op in matcher.opcodes()) {
            if (op.tag != "replace") continue
            if (op.i2 - op.i1 !in 1..MAX_SUGGESTION_WORDS || op.j2 - op.j1 !in 1..MAX_SUGGESTION_WORDS) continue
            val from = a.subList(op.i1, op.i2).joinToString(" ")
            val to = b.subList(op.j1, op.j2).joinToString(" ")
            if (key(from) == key(to) || !seen.add(key(from) to to)) continue
            out += Replacement(from, to)
            if (out.size >= MAX_SUGGESTIONS) break
        }
        return out
    }
}

/**
 * The parts of Python's difflib.SequenceMatcher that `suggest_replacements` uses
 * (autojunk off, no junk): matching blocks, opcodes and ratio, same algorithm.
 */
internal class SequenceMatcher(private val a: List<String>, private val b: List<String>) {
    data class Opcode(val tag: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

    private val b2j: Map<String, List<Int>> = b.indices.groupBy { b[it] }

    private fun longestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
        var bestI = alo
        var bestJ = blo
        var bestSize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                next[j] = k
                if (k > bestSize) {
                    bestI = i - k + 1
                    bestJ = j - k + 1
                    bestSize = k
                }
            }
            j2len = next
        }
        return Triple(bestI, bestJ, bestSize)
    }

    private val blocks: List<Triple<Int, Int, Int>> by lazy {
        val queue = ArrayDeque(listOf(intArrayOf(0, a.size, 0, b.size)))
        val found = ArrayList<Triple<Int, Int, Int>>()
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val (i, j, k) = longestMatch(alo, ahi, blo, bhi)
            if (k > 0) {
                found += Triple(i, j, k)
                if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        found.sortWith(compareBy({ it.first }, { it.second }))
        // Collapse adjacent blocks, as difflib does.
        val merged = ArrayList<Triple<Int, Int, Int>>()
        var (i1, j1, k1) = Triple(0, 0, 0)
        for ((i2, j2, k2) in found) {
            if (i1 + k1 == i2 && j1 + k1 == j2) {
                k1 += k2
            } else {
                if (k1 > 0) merged += Triple(i1, j1, k1)
                i1 = i2; j1 = j2; k1 = k2
            }
        }
        if (k1 > 0) merged += Triple(i1, j1, k1)
        merged += Triple(a.size, b.size, 0)
        merged
    }

    fun ratio(): Double {
        val matches = blocks.sumOf { it.third }
        val total = a.size + b.size
        return if (total == 0) 1.0 else 2.0 * matches / total
    }

    fun opcodes(): List<Opcode> {
        var i = 0
        var j = 0
        val out = ArrayList<Opcode>()
        for ((ai, bj, size) in blocks) {
            val tag = when {
                i < ai && j < bj -> "replace"
                i < ai -> "delete"
                j < bj -> "insert"
                else -> null
            }
            if (tag != null) out += Opcode(tag, i, ai, j, bj)
            i = ai + size
            j = bj + size
            if (size > 0) out += Opcode("equal", ai, i, bj, j)
        }
        return out
    }
}

package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.refine.RefinementPrompt
import io.github.mvoloch.voloz.android.refine.RefinementPromptText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures in src/test/resources/refinement were produced by the desktop's own
 * Python `build_refinement_prompt` / `collapse_repetitive_artifacts`
 * (voloz-088 backend/services/refinement.py) via gen_prompt.py.
 */
class RefinementPromptTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("refinement/$name")!!.readText(Charsets.UTF_8)

    @Test
    fun systemPromptMatchesDesktopWithoutVocabulary() {
        assertEquals(fixture("prompt_no_vocab.txt"), RefinementPrompt.buildSystemPrompt(emptyList()))
    }

    @Test
    fun systemPromptMatchesDesktopWithVocabulary() {
        assertEquals(
            fixture("prompt_with_vocab.txt"),
            RefinementPrompt.buildSystemPrompt(listOf("Voloz", "Pascoal", " ", "MSC", "Log-In")),
        )
    }

    @Test
    fun examplesMatchDesktop() {
        val expected = Json.parseToJsonElement(fixture("examples.json")).jsonArray.map {
            val pair = it.jsonArray
            pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content
        }
        assertEquals(expected, RefinementPromptText.EXAMPLES)
    }

    @Test
    fun messagesAreSystemThenFewShotThenTranscript() {
        val msgs = RefinementPrompt.buildMessages("oi tudo bem", listOf("Voloz"))
        assertEquals(1 + 2 * RefinementPromptText.EXAMPLES.size + 1, msgs.size)
        val roles = msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals("system", roles.first())
        assertEquals("user", roles.last())
        for (i in 1 until msgs.size - 1) assertEquals(if (i % 2 == 1) "user" else "assistant", roles[i])
        assertEquals("oi tudo bem", msgs.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(msgs.first().jsonObject["content"]!!.jsonPrimitive.content.contains("Voloz"))
    }

    @Test
    fun sttPromptJoinsTermsAndCapsLength() {
        assertNull(RefinementPrompt.buildSttPrompt(emptyList()))
        assertNull(RefinementPrompt.buildSttPrompt(listOf(" ", "")))
        assertEquals("Voloz, Pascoal, Log In", RefinementPrompt.buildSttPrompt(listOf("Voloz", " Pascoal ", "Log   In")))
        val many = List(100) { "termo$it" }
        val prompt = RefinementPrompt.buildSttPrompt(many)!!
        assertTrue(prompt.length <= RefinementPrompt.STT_PROMPT_MAX_CHARS)
        assertTrue(prompt.endsWith(prompt.split(", ").last())) // whole terms only
    }

    @Test
    fun vocabularyNormalisation() {
        assertEquals(
            listOf("Voloz", "Log In", "MSC"),
            RefinementPrompt.normalizeVocabulary(listOf(" Voloz ", "voloz", "Log  In", "", "MSC", "x".repeat(101))),
        )
    }

    @Test
    fun collapseMatchesDesktop() {
        val cases = Json.parseToJsonElement(fixture("collapse_cases.json")).jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val input = c.jsonArray[0].jsonPrimitive.content
            val expected = c.jsonArray[1].jsonPrimitive.content
            assertEquals("input: $input", expected, RefinementPrompt.collapseRepetitiveArtifacts(input))
        }
    }

    /**
     * Android compiles regexes with ICU, which rejects the JVM-only (?U) flag
     * (PatternSyntaxException in RefinementPrompt.<clinit>, seen on an API 34
     * emulator). The JVM test run cannot see that, so guard the pattern text and
     * check the Unicode behaviour (\\s / \\w as in Python's re) explicitly.
     */
    @Test
    fun patternsCompileOnAndroidIcu() {
        for (p in listOf(RefinementPrompt.WHITESPACE_PATTERN, RefinementPrompt.NON_WORD_PATTERN)) {
            assertFalse("inline flag group in $p", p.contains("(?"))
        }
        // Accented words still count as the same token (NON_WORD keeps letters + marks).
        assertEquals("", RefinementPrompt.collapseRepetitiveArtifacts("não não não não não não"))
        assertEquals("ok", RefinementPrompt.collapseRepetitiveArtifacts("ok é, é! é é é é"))
        // Non-breaking and other Unicode spaces split words like ASCII spaces.
        assertEquals("fim", RefinementPrompt.collapseRepetitiveArtifacts("olá\u00A0olá\u2009olá olá olá olá fim"))
        assertEquals("Voloz Pascoal", RefinementPrompt.normalizeVocabulary(listOf("Voloz\u00A0 Pascoal")).single())
    }
}

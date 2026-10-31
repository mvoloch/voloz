package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.net.ApiClient
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.Fallback
import io.github.mvoloch.voloz.android.net.Provider
import io.github.mvoloch.voloz.android.net.ProviderPresets
import io.github.mvoloch.voloz.android.net.UploadAudio
import io.github.mvoloch.voloz.android.text.Replacement
import io.github.mvoloch.voloz.android.text.Replacements
import io.github.mvoloch.voloz.android.text.Snippet
import io.github.mvoloch.voloz.android.text.Snippets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases ported from the desktop's backend/tests/test_vocabulary.py and test_snippets.py. */
class TextRulesTest {
    private val rule = Replacement("Valois", "Voloz")
    private val address = "Rua das Flores, 123\nApto 45\n01234-567 São Paulo SP"
    private val signature = "Abraço,\nMarcus"
    private val snippets = listOf(Snippet("meu endereço", address), Snippet("assinatura", signature))

    // ---- replacements --------------------------------------------------------------

    @Test
    fun replacementsAreTrimmedAndALaterRuleForTheSamePhraseWins() {
        val rules = Replacements.normalize(
            listOf(
                Replacement(" Valois ", "Valoá"),
                Replacement("", "x"),
                Replacement("same", "same"),
                Replacement("log  in", "Log-In"),
                Replacement("VALOIS", "Voloz"),
            ),
        )
        assertEquals(listOf(Replacement("log in", "Log-In"), Replacement("VALOIS", "Voloz")), rules)
    }

    @Test
    fun replacementIsCaseInsensitiveAndWholeWord() {
        val text = "O Valois disse: valois, VALOIS! Valoisa e Valoisé ficam; SuperValois também."
        assertEquals(
            "O Voloz disse: Voloz, Voloz! Valoisa e Valoisé ficam; SuperValois também.",
            Replacements.apply(text, listOf(rule)),
        )
    }

    @Test
    fun replacementHandlesPortugueseAccents() {
        val rules = listOf(Replacement("conceicao", "Conceição"), Replacement("você", "o senhor"))
        assertEquals(
            "A Conceição falou com o senhor; conceicaozinha e vocês ficam.",
            Replacements.apply("A Conceicao falou com VOCÊ; conceicaozinha e vocês ficam.", rules),
        )
    }

    @Test
    fun replacementMatchesPhrasesAcrossWhitespaceAndRunsOnce() {
        val rules = listOf(
            Replacement("log in", "Log-In"),
            Replacement("log", "registro"),
            Replacement("alfa", "beta"),
            Replacement("beta", "gama"),
        )
        assertEquals("a Log-In e o registro; beta gama", Replacements.apply("a log\nin e o log; alfa beta", rules))
    }

    @Test
    fun replacementWithoutRulesOrTextIsANoOp() {
        assertEquals("Valois", Replacements.apply("Valois", emptyList()))
        assertEquals("", Replacements.apply("", listOf(rule)))
        assertEquals("Valois", Replacements.apply("Valois", listOf(Replacement(" ", "x"))))
        assertEquals("C++ e c++", Replacements.apply("cpp e c++", listOf(Replacement("cpp", "C++"))))
    }

    // ---- suggestions from a hand correction ---------------------------------------------

    @Test
    fun suggestionsFindAMisheardName() {
        assertEquals(
            listOf(rule),
            Replacements.suggest("Hoje o Valois abriu o app e falou com o Pascoal.", "Hoje o Voloz abriu o app e falou com o Pascoal."),
        )
    }

    @Test
    fun suggestionsHandleMultiWordSides() {
        assertEquals(
            listOf(Replacement("log in", "Log-In")),
            Replacements.suggest("faça o log in no sistema da log in", "faça o Log-In no sistema da Log-In"),
        )
        assertEquals(
            listOf(Replacement("João da", "Jon")),
            Replacements.suggest("fale com João da Silva hoje", "fale com Jon Silva hoje"),
        )
    }

    @Test
    fun suggestionsIgnoreCasePunctuationInsertionsAndRewrites() {
        val before = "o voloz chegou, e o pascoal saiu"
        assertEquals(emptyList<Replacement>(), Replacements.suggest(before, "O Voloz chegou. E o Pascoal saiu!"))
        assertEquals(emptyList<Replacement>(), Replacements.suggest(before, "o voloz chegou cedo, e o pascoal saiu"))
        assertEquals(emptyList<Replacement>(), Replacements.suggest(before, "o voloz, e o pascoal saiu"))
        val ten = "um dois três quatro cinco seis sete oito nove dez"
        assertEquals(emptyList<Replacement>(), Replacements.suggest(ten, "um dois A B C D sete oito nove dez"))
        assertEquals(emptyList<Replacement>(), Replacements.suggest(ten, "completamente outra frase sobre outro assunto qualquer"))
        assertEquals(emptyList<Replacement>(), Replacements.suggest("", "Voloz"))
    }

    // ---- text shortcuts ------------------------------------------------------------------

    @Test
    fun aTranscriptThatIsOnlyTheTriggerBecomesTheText() {
        for (t in listOf("meu endereço", "Meu endereço.", "MEU ENDERECO!", "  meu,   endereço?  ", "É... meu endereço.", "Hum, meu endereço, tipo.")) {
            assertEquals(t, address, Snippets.matchWhole(t, snippets))
        }
        for (t in listOf("", "   ", "meu endereço novo", "o meu endereço", "endereço", "meu endereço 2")) {
            assertNull(t, Snippets.matchWhole(t, snippets))
        }
    }

    @Test
    fun theLongerTriggerWinsAWholeMatch() {
        val more = snippets + Snippet("meu endereço comercial", "Av. Paulista, 1000")
        assertEquals("Av. Paulista, 1000", Snippets.matchWhole("Meu endereço comercial.", more))
        assertEquals(address, Snippets.matchWhole("Meu endereço.", more))
    }

    @Test
    fun triggersInsideADictationAreExpandedInPlaceOnce() {
        assertEquals(
            "Pode mandar para $address, e no fim a $signature.",
            Snippets.expand("Pode mandar para Meu Endereco, e no fim a assinatura.", snippets),
        )
        val email = listOf(Snippet("email", "marcus@example.com email"))
        assertEquals("emails: marcus@example.com email.", Snippets.expand("emails: email.", email))
        assertEquals("nothing here", Snippets.expand("nothing here", email))
        assertEquals("Die Street, bitte.", Snippets.expand("Die Straße, bitte.", listOf(Snippet("strasse", "Street"))))
    }

    @Test
    fun placeholdersStandForEachTriggerAndRestore() {
        val (protected, tokens) = Snippets.protect("Meu endereço e a assinatura; de novo meu endereco.", snippets)
        assertEquals("[[S1]] e a [[S2]]; de novo [[S3]].", protected)
        assertEquals(mapOf("[[S1]]" to address, "[[S2]]" to signature, "[[S3]]" to address), tokens)
        assertEquals("$address, e a $signature. De novo: $address.", Snippets.restore("[[S1]], e a [[S2]]. De novo: [[S3]].", tokens))
        assertEquals("nada aqui" to emptyMap<String, String>(), Snippets.protect("nada aqui", snippets))
        val (_, two) = Snippets.protect("meu endereço e a assinatura", snippets)
        for (bad in listOf("[[S1]] e a.", "[[S1]] e a [[S2]] [[S2]].", "[[S1]] e a [[S3]].", "[[S1]] e a [[S2]] [[S9]].")) {
            assertNull(bad, Snippets.restore(bad, two))
        }
    }

    // ---- in the pipeline -------------------------------------------------------------------

    private class Server(val stt: String, val refined: (String) -> String) {
        val bodies = mutableListOf<String>()
        fun client() = ApiClient(
            OkHttpClient.Builder().addInterceptor(
                Interceptor { chain ->
                    val req = chain.request()
                    val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
                    bodies += body
                    val reply = if (req.url.encodedPath.endsWith("/audio/transcriptions")) {
                        """{"text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(stt))}}"""
                    } else {
                        val user = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
                        """{"choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(refined(user)))}},"finish_reason":"stop"}]}"""
                    }
                    Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                        .body(reply.toResponseBody("application/json".toMediaType())).build()
                },
            ).build(),
        )
    }

    private val route = DictationPipeline.Route(
        listOf(Provider(ProviderPresets.GROQ, "whisper-large-v3")),
        listOf(Provider(ProviderPresets.GROQ, "openai/gpt-oss-120b")),
    ) { "k" }
    private val audio = UploadAudio(byteArrayOf(0), "a.flac", "audio/flac")
    private val rules = DictationPipeline.TextRules(listOf(rule), snippets)

    @Test
    fun aDictationThatIsOnlyATriggerSkipsRefinement() {
        val server = Server("Meu endereço.") { error("no refinement expected") }
        val out = DictationPipeline(server.client(), Fallback { 0L }).run(route, audio, emptyList(), refine = true, rules = rules)
        assertEquals(address, out.text)
        assertTrue(out.shortcut)
        assertEquals(1, server.bodies.size)
    }

    @Test
    fun aTriggerInsideADictationGoesThroughRefinementAsAPlaceholder() {
        val server = Server("manda pra meu endereço por favor, valois") { user -> "Manda pra [[S1]], por favor, Voloz." }
        val out = DictationPipeline(server.client(), Fallback { 0L }).run(route, audio, emptyList(), refine = true, rules = rules)
        assertEquals("Manda pra $address, por favor, Voloz.", out.text)
        assertFalse(out.shortcut)
        val chat = server.bodies.last()
        assertTrue(chat.contains("manda pra [[S1]] por favor, Voloz")) // replacement applied, trigger hidden
        assertFalse(chat.contains("Rua das Flores"))
        assertTrue(chat.contains("Copy each of these placeholders exactly once"))
    }

    @Test
    fun aLostPlaceholderInsertsTheUnrefinedTextWithShortcuts() {
        val server = Server("manda pra meu endereço por favor") { "Manda, por favor." }
        val out = DictationPipeline(server.client(), Fallback { 0L }).run(route, audio, emptyList(), refine = true, rules = rules)
        assertEquals("manda pra $address por favor", out.text)
        assertTrue(out.shortcut)
    }

    @Test
    fun withRefinementOffTriggersAreExpandedAndRulesApplied() {
        val server = Server("o valois mora em meu endereço") { error("no refinement expected") }
        val out = DictationPipeline(server.client(), Fallback { 0L }).run(route, audio, emptyList(), refine = false, rules = rules)
        assertEquals("o Voloz mora em $address", out.text)
    }

    @Test
    fun withoutATriggerThePromptHasNoPlaceholderLine() {
        val server = Server("bom dia a todos") { "Bom dia a todos." }
        val out = DictationPipeline(server.client(), Fallback { 0L }).run(route, audio, emptyList(), refine = true, rules = rules)
        assertEquals("Bom dia a todos.", out.text)
        assertFalse(server.bodies.last().contains("placeholders"))
    }

    @Test
    fun refiningAgainFromTheHistoryTakesTheSamePath() {
        val server = Server("unused") { user -> "Manda pra [[S1]], por favor." }
        val pipeline = DictationPipeline(server.client(), Fallback { 0L })
        val again = pipeline.refineAgain(route, "manda pra meu endereço por favor", emptyList(), rules)
        assertEquals("Manda pra $address, por favor.", again.text)
        assertEquals("openai/gpt-oss-120b", again.answer?.provider?.model)
        val whole = pipeline.refineAgain(route, "meu endereço", emptyList(), rules)
        assertEquals(address, whole.text)
        assertTrue(whole.shortcut)
        assertEquals(1, server.bodies.size) // the whole shortcut didn't call the model
    }
}

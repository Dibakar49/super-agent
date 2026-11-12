package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.test.*

class QuestionsTest {
    @Test fun `multipart answer strings match the original TypeScript questionnaire`() {
        val fixtures = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/question-parts-typescript-parity.json")).bufferedReader().use { it.readText() }).jsonArray
        for (row in fixtures) {
            val item = row.jsonObject
            val parts = Json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(QuestionPart.serializer()), item["parts"]!!)
            val selected = item["selected"]!!.jsonArray.map { it.jsonPrimitive.content }
            val typed = item["typed"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(item["expected"]!!.jsonPrimitive.content, partsAnswer(parts, selected, typed))
        }
    }

    @Test fun `parts keep optional suggestions and accept a typed answer`() {
        val call = ToolCall("question", "ask_person", Json.parseToJsonElement("""{"question":" Which city? ","parts":[{"header":" City ","question":" Where are you? ","options":[]}]}""").jsonObject)
        val (text, why, parts) = parseQuestion(call)
        assertEquals("Which city?", text)
        assertNull(why)
        assertEquals(QuestionPart("City", "Where are you?"), parts.single())
        assertEquals("City: Kolkata", partsAnswer(parts, listOf(""), listOf(" Kolkata ")))
        assertEquals("City: Kolkata, Central", partsAnswer(parts, listOf("Kolkata"), listOf(" Central ")))
        assertFails { partsAnswer(parts, listOf(""), listOf("  ")) }
        assertEquals("In response to your question: Which city?\n\nKolkata", questionResponseMessage(text, "Kolkata"))
    }

    @Test fun `empty questions and blank or excessive parts are refused`() {
        for (arguments in listOf("""{"question":"  "}""", """{"question":"City?","parts":[]}""", """{"question":"City?","parts":[{"header":" ","question":"Where?"}]}""")) {
            assertFails { parseQuestion(ToolCall("q", "ask_person", Json.parseToJsonElement(arguments).jsonObject)) }
        }
        val parts = List(7) { QuestionPart("City", "Where?") }
        val args =
            buildJsonObject {
                put("question", "City?")
                put("parts", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(QuestionPart.serializer()), parts))
            }
        assertFails { parseQuestion(ToolCall("q", "ask_person", args)) }
    }
}

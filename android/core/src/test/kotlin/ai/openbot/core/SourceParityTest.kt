package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

class SourceParityTest {
    @Test fun `theme conversion matches Chromium rendering of the original CSS tokens`() {
        val rows = Json.parseToJsonElement(javaClass.getResource("/theme-browser-parity.json")!!.readText()).jsonArray
        rows.forEach { row ->
            val item = row.jsonObject
            val value = item["value"]!!.jsonPrimitive.content
            val numbers = Regex("[0-9.]+").findAll(value).map { it.value.toDouble() }.toList()
            val opacity = numbers.getOrElse(3) { 1.0 } / if (value.contains('%'))100.0 else 1.0
            val color = oklchArgb(numbers[0], numbers[1], numbers[2], opacity)
            val expected = item["rgba"]!!.jsonArray.map { it.jsonPrimitive.int }
            // Compare painted colors, not un-premultiplied readback: at 12% opacity the latter
            // amplifies an 8-bit rounding difference by over eight times.
            val actual = listOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255, (color ushr 24) and 255)
            assertEquals(expected[3], actual[3])
            for (index in 0..2) {
                val paintedExpected = (expected[index] * expected[3] + 255 * (255 - expected[3])) / 255.0
                val paintedActual = (actual[index] * actual[3] + 255 * (255 - actual[3])) / 255.0
                assertTrue(abs(paintedExpected - paintedActual) <= 1.1, "${item["key"]}: $expected vs $actual")
            }
        }
    }

    @Test fun `skill selection reproduces the unchanged TypeScript helper including command boundaries`() {
        val rows = Json.parseToJsonElement(javaClass.getResource("/skills-typescript-parity.json")!!.readText()).jsonArray
        val held = listOf(LocalSkill("standup", "Standup notes", "", "Summarise the work."))
        rows.forEach { row ->
            val item = row.jsonObject
            val text = item["text"]!!.jsonPrimitive.content
            assertEquals(item["command"]!!.jsonPrimitive.contentOrNull, leadingSkillCommand(text))
            assertEquals(item["guidance"]!!.jsonPrimitive.contentOrNull, pickedSkillGuidance(text, held))
        }
        assertEquals("standup", leadingSkillCommand("  /standup\nDo this"))
        assertNull(pickedSkillGuidance("/private-other-skill do it", held))
    }

    @Test fun `a long selected skill is preserved in the prompt instead of being silently cut`() {
        val instructions = "Keep this exact instruction.\n".repeat(120) + "IMPORTANT_END_MARKER"
        val prompt = localPrompt(LocalBot("b", "Bot", instructions), emptyList(), listOf(LocalMessage("m", "c", "user", "Hello", "t")))
        assertContains(prompt, "IMPORTANT_END_MARKER")
    }

    @Test fun `skills validate original field limits without granting the tools they declare`() {
        val skill = LocalSkill("standup", "Standup notes", "One line", "Follow these instructions", tools = listOf("private/send"))
        validateSkill(skill)
        assertContains(pickedSkillGuidance("/standup now", listOf(skill))!!, "Follow these instructions")
        assertFalse(pickedSkillGuidance("/standup now", listOf(skill))!!.contains("private/send"))
        listOf("a", "Upper", "bad_underscore", "-bad", "bad-", "x".repeat(41)).forEach { command -> assertFailsWith<IllegalArgumentException> { validateSkill(skill.copy(slug = command)) } }
    }

    @Test fun `conversation search matches visible metadata and pinning preserves recency order`() {
        val older = LocalConversation("a", "bot", "Assistant", "2026-01-01T00:00:00Z", opening = "Write a report", pinned = true)
        val newer = LocalConversation("b", "bot", "Assistant", "2026-01-02T00:00:00Z", summary = "Planning", lastMessage = "Review the budget")
        assertEquals(listOf(newer), matchingConversations(listOf(newer, older), "  budget "))
        assertEquals(listOf(older, newer), pinnedConversationsFirst(listOf(newer, older)))
        assertEquals("Write a report", conversationTitle(older))
        assertFalse(conversationUnread(newer.copy(lastMessageAgentId = "bot", lastMessageAt = "2026-01-03T00:00:00Z"), "b"))
    }

    @Test fun `memory context quoting and record budgets match the original TypeScript`() {
        val rows = Json.parseToJsonElement(javaClass.getResource("/memory-typescript-parity.json")!!.readText()).jsonArray
        rows.forEach { row ->
            val obj = row.jsonObject
            val memories =
                obj["memories"]!!.jsonArray.map { item ->
                    val m = item.jsonObject
                    Memory(m["id"]!!.jsonPrimitive.content, m["content"]!!.jsonPrimitive.content, m["provenance"]!!.jsonPrimitive.content, true, ReviewState.CONFIRMED, FormedBy.PERSON, "t")
                }
            assertEquals(obj["context"]!!.jsonPrimitive.content, quoteMemoryContext(memories))
        }
    }
}

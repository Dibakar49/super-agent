package ai.openbot.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ContractsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `wire status names remain compatible with the TypeScript API`() {
        assertEquals("\"needs_input\"", json.encodeToString(TaskStatus.NEEDS_INPUT))
        assertEquals("\"pre_approved\"", json.encodeToString(RuleBehaviour.PRE_APPROVED))
        assertEquals("\"import\"", json.encodeToString(FormedBy.IMPORT))
        val message = json.decodeFromString<TranscriptMessage>("""{"id":"m","role":"assistant","content":[{"type":"text","text":"Hello"}],"newServerField":true}""")
        assertEquals("Hello", message.content!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun `approval actions retain unknown argument and target structures`() {
        val body = """{"requests":[{"id":"r","status":"pending","action":{"botId":"b","toolRef":"mail/send","effect":"write","scope":"account","args":{"to":["person@example.com"]},"policy":{"source":"task","reason":"Ask each time"}}}],"questions":[]}"""
        val inbox = json.decodeFromString<ApprovalInbox>(body)
        assertEquals("task", inbox.requests.single().action!!.policy!!.source)
        assertEquals(json.parseToJsonElement(body), json.parseToJsonElement(json.encodeToString(inbox)))
    }

    @Test fun `routine firing round trips empty whitespace and multi-line instructions`() {
        listOf("", "   ", "  do the thing  ", "Check the board.\n\nThen post what changed.").forEach {
            assertEquals(it, readFiring(frameFiring(it)))
        }
        assertNull(readFiring(FIRING_FRAME.first()))
        assertNull(readFiring("Check the board every morning"))
        assertEquals(FIRING_FRAME.joinToString("\n") + "\n\nRun now", frameFiring("Run now"))
    }

    @Test fun `notification destinations reject credential path fragment and foreign scheme injection`() {
        val rejected = listOf<String?>(null, "https://conversation?channelId=c", "openbotmobile://user:pass@conversation?channelId=c",
            "openbotmobile://conversation:8080?channelId=c", "openbotmobile://conversation/evil?channelId=c",
            "openbotmobile://conversation?channelId=c#x", "openbotmobile://unknown?channelId=c",
            "openbotmobile://conversation", "openbotmobile://conversation?channelId=", "openbotmobile://needs-you?anything=1",
            "openbotmobile://conversation?channelId=" + "x".repeat(201))
        rejected.forEach { assertNull(notificationDestination(it), it) }
        assertEquals(NotificationDestination.NeedsYou, notificationDestination("openbotmobile://needs-you"))
        assertEquals(NotificationDestination.NeedsYou, notificationDestination("openbotmobile://needs-you?#"))
        assertEquals(NotificationDestination.Conversation("a/b", "r"), notificationDestination("openbotmobile://conversation?channelId=a%2Fb&requestId=r"))
        assertEquals(NotificationDestination.Approvals("c"), notificationDestination("openbotmobile://approvals/?channelId=c"))
        // URLSearchParams reads the first occurrence, matching the original parser.
        assertEquals(NotificationDestination.Conversation("first"), notificationDestination("openbotmobile://conversation?channelId=first&channelId=second"))
    }

    @Test fun `waiting tasks use review answer retry ordering with newest first`() {
        fun task(id: String, status: TaskStatus, date: String) = BotTask(id, "b", "c", id, status, updatedAt = date)
        val input = listOf(task("failed", TaskStatus.FAILED, "2026-11-09T08:00:00Z"),
            task("old", TaskStatus.READY_FOR_REVIEW, "2026-11-08T08:00:00Z"),
            task("working", TaskStatus.WORKING, "2026-11-09T08:00:00Z"),
            task("new", TaskStatus.READY_FOR_REVIEW, "2026-11-09T08:00:00Z"),
            task("answer", TaskStatus.NEEDS_INPUT, "2026-11-09T08:00:00Z"))
        assertEquals(listOf("new", "old", "answer", "failed"), waitingTasks(input).map { it.id })
        assertEquals("failed", input.first().id) // No mutation of the caller's list.
    }

    @Test fun `source links allow web pages only`() {
        assertNotNull(webLink("https://example.com/path"))
        listOf(null, "javascript:alert(1)", "file:///etc/passwd", "openbotmobile://needs-you", "not a url").forEach { assertNull(webLink(it)) }
    }

    @Test fun `local prompts omit disabled memories and incomplete assistant messages`() {
        val bot = LocalBot("b", "Bot", "Explain clearly")
        fun memory(id: String, enabled: Boolean) = Memory(id, id, "You", enabled, ReviewState.CONFIRMED, FormedBy.PERSON, "2026-11-09T08:00:00Z")
        val history = listOf(LocalMessage("failed", "c", "assistant", "FAILURE_MUST_NOT_ENTER_PROMPT", "t", MessageState.FAILED), LocalMessage("user", "c", "user", "Hello", "t"))
        val prompt = localPrompt(bot, listOf(memory("ENABLED_FACT", true), memory("DISABLED_FACT", false)), history)
        assertContains(prompt, "ENABLED_FACT")
        assertFalse(prompt.contains("DISABLED_FACT"))
        assertFalse(prompt.contains("FAILURE_MUST_NOT_ENTER_PROMPT"))
        assertTrue(prompt.endsWith("Person: Hello\nBot:"))
        assertFailsWith<IllegalArgumentException> { localPrompt(bot.copy(paused = true), emptyList(), history) }
        assertFailsWith<IllegalArgumentException> { localPrompt(bot, emptyList(), listOf(history.last().copy(text = "x".repeat(8000))),maximumCharacters=8000) }
    }
}

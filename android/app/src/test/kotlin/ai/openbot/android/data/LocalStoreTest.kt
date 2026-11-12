package ai.openbot.android.data

import ai.openbot.core.*
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalStoreTest {
    private lateinit var context: Context
    private lateinit var store: LocalStore

    @BeforeTest fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("openbot.db")
        store = LocalStore(context)
    }

    @AfterTest fun teardown() {
        store.close()
        context.deleteDatabase("openbot.db")
    }

    @Test fun `bot edits preserve conversations and data survives reopening`() {
        val bot = store.bots().single()
        val conversation = store.newConversation(bot.id)
        store.saveMessage(LocalMessage("m", conversation.id, "user", "Keep this message", "2026-11-09T08:00:00Z"))
        store.saveBot(bot.copy(name = "Renamed", paused = true))
        store.close()
        store = LocalStore(context)
        assertEquals("Renamed", store.bots().single().name)
        assertTrue(store.bots().single().paused)
        assertEquals(conversation.id, store.conversations().single().id)
        assertEquals("Keep this message", store.messages(conversation.id).single().text)
    }

    @Test fun `recovery marks only unfinished generation interrupted`() {
        val conversation = store.newConversation(store.bots().single().id)
        store.saveMessage(LocalMessage("a", conversation.id, "assistant", "pending", "2026-11-09T08:00:00Z", MessageState.GENERATING))
        store.saveMessage(LocalMessage("b", conversation.id, "assistant", "Done", "2026-11-09T08:00:01Z"))
        store.recoverInterruptedMessages()
        store.recoverInterruptedMessages()
        val messages = store.messages(conversation.id)
        assertEquals(MessageState.INTERRUPTED, messages[0].state)
        assertEquals("Done", messages[1].text)
        assertEquals(MessageState.COMPLETE, messages[1].state)
    }

    @Test fun `chosen title survives new messages and clearing restores the generated summary`() {
        val channel = store.newConversation(store.bots().single().id)
        store.saveConversation(channel.copy(summary = "Generated summary"))
        store.renameConversation(channel.id, "  Holiday planning  ")
        store.saveMessage(LocalMessage("new", channel.id, "assistant", "New reply", "2026-11-12T10:00:00Z"))
        store.close()
        store = LocalStore(context)
        val renamed = store.conversations().single()
        assertEquals("Holiday planning", conversationTitle(renamed))
        assertEquals(listOf(renamed), matchingConversations(listOf(renamed), "holiday"))
        assertFailsWith<IllegalArgumentException> { store.renameConversation(channel.id, "x".repeat(61)) }
        assertEquals("Holiday planning", store.conversations().single().title)
        store.renameConversation(channel.id, "  ")
        assertNull(store.conversations().single().title)
        assertEquals("Generated summary", conversationTitle(store.conversations().single()))
    }

    @Test fun `process recovery preserves partial streamed text without replaying a turn`() {
        val channel = store.newConversation(store.bots().single().id)
        store.saveMessage(LocalMessage("stream", channel.id, "assistant", "A saved partial answer", "2026-11-12T10:00:00Z", MessageState.GENERATING))
        store.recoverInterruptedMessages()
        store.recoverInterruptedMessages()
        val answer = store.messages(channel.id).single()
        assertEquals(MessageState.INTERRUPTED, answer.state)
        assertContains(answer.text, "A saved partial answer")
        assertEquals(1, Regex("Generation was interrupted").findAll(answer.text).count())
        assertTrue(store.checkpoints().isEmpty())
    }

    @Test fun `turn writes are atomic when a message identity conflicts`() {
        val conversation = store.newConversation(store.bots().single().id)
        store.saveMessage(LocalMessage("existing", conversation.id, "assistant", "Keep", "2026-11-09T08:00:00Z"))
        // A constraint failure during the assistant write must roll back the preceding user write.
        store.writableDatabase.execSQL("CREATE TRIGGER reject_test_reply BEFORE UPDATE ON messages WHEN NEW.id = 'existing' BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
        assertFails {
            store.beginTurn(
                LocalMessage("new-user", conversation.id, "user", "Hello", "2026-11-09T08:00:01Z"),
                LocalMessage("existing", conversation.id, "assistant", "Pending", "2026-11-09T08:00:02Z", MessageState.GENERATING),
            )
        }
        assertEquals(listOf("Keep"), store.messages(conversation.id).map { it.text })
    }

    @Test fun `memory updates keep identity and deletion persists`() {
        val memory = Memory("m", "Original", "You", true, ReviewState.CONFIRMED, FormedBy.PERSON, "2026-11-09T08:00:00Z")
        store.saveMemory(memory)
        store.saveMemory(memory.copy(content = "Changed", enabled = false))
        assertEquals(1, store.memories().size)
        assertFalse(store.memories().single().enabled)
        assertEquals("Changed", store.memories().single().content)
        assertFailsWith<IllegalArgumentException> { store.saveMemory(memory.copy(content = " ")) }
        store.deleteMemory(memory.id)
        assertTrue(store.memories().isEmpty())
    }

    @Test fun `skills and general preferences persist without replacing conversations`() {
        val conversation = store.newConversation(store.bots().single().id)
        store.saveSkill(LocalSkill("standup", "Standup notes", "Summary", "Do the work"), creating = true)
        assertFails { store.saveSkill(LocalSkill("standup", "Duplicate", "", "Other"), creating = true) }
        store.savePreferences(LocalPreferences(name = "Asha", standingInstructions = "Be concise", darkTheme = true))
        store.close()
        store = LocalStore(context)
        assertEquals("Do the work", store.skills().single().instructions)
        assertEquals("Asha", store.preferences().name)
        assertTrue(store.preferences().darkTheme!!)
        assertEquals(conversation.id, store.conversations().single().id)
        store.deleteSkill("standup")
        assertTrue(store.skills().isEmpty())
    }

    @Test fun `conversation metadata retains the first user words after the Bot replies`() {
        val conversation = store.newConversation(store.bots().single().id)
        store.saveMessage(LocalMessage("u", conversation.id, "user", "First question", "2026-11-09T08:00:00Z"))
        store.saveMessage(LocalMessage("a", conversation.id, "assistant", "Latest answer", "2026-11-09T08:01:00Z"))
        val updated = store.conversations().single()
        assertEquals("First question", conversationTitle(updated))
        assertEquals("Latest answer", updated.lastMessage)
        assertTrue(conversationUnread(updated, null))
        store.saveConversation(updated.copy(lastReadAt = "2026-11-09T08:02:00Z"))
        assertFalse(conversationUnread(store.conversations().single(), null))
        store.deleteConversation(updated.id)
        assertTrue(store.messages(updated.id).isEmpty())
    }

    @Test fun `legacy version one data survives schema upgrades and unchanged long instructions`() {
        store.close()
        context.deleteDatabase("openbot.db")
        val db = context.openOrCreateDatabase("openbot.db", 0, null)
        db.execSQL("CREATE TABLE bots(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE conversations(id TEXT PRIMARY KEY,bot_id TEXT NOT NULL REFERENCES bots(id),created_at TEXT NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,created_at TEXT NOT NULL,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY,updated_at TEXT NOT NULL,payload TEXT NOT NULL)")
        val bot = LocalBot("legacy", "Legacy", "x".repeat(2000))
        db.execSQL(
            "INSERT INTO bots VALUES(?,?)",
            arrayOf(
                bot.id,
                kotlinx.serialization.json.Json
                    .encodeToString(LocalBot.serializer(), bot),
            ),
        )
        db.version = 1
        db.close()
        store = LocalStore(context)
        assertEquals(bot, store.bots().single())
        store.saveBot(bot.copy(paused = true))
        assertTrue(store.bots().single().paused)
        assertFails { store.saveBot(bot.copy(instruction = "y".repeat(2000))) }
        assertTrue(store.skills().isEmpty())
        assertTrue(store.routines().isEmpty())
    }

    @Test fun `routine schedule claiming failure fatigue and history are durable`() {
        val channel = store.newConversation(store.bots().single().id)
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val routine = store.saveRoutine(LocalRoutine("r", channel.botId, channel.id, "Summarise the day", cron = "*/15 * * * *", nextRunAt = now.toString(), createdAt = now.toString()), now)
        assertEquals("2026-01-01T00:15:00Z", routine.nextRunAt)
        assertNull(store.startRoutineRun("r", false, now))
        val due = now.plusSeconds(900)
        val run = assertNotNull(store.startRoutineRun("r", false, due))
        assertNull(store.startRoutineRun("r", false, due))
        assertFails { store.startRoutineRun("r", true, due) }
        store.finishRoutineRun(run, RoutineStatus.FAILED, "bad")
        store.finishRoutineRun(run, RoutineStatus.SUCCEEDED)
        assertEquals(RoutineStatus.FAILED, store.routineRuns("r").single().status)
        repeat(9) { store.finishRoutineRun(assertNotNull(store.startRoutineRun("r", true, due)), RoutineStatus.FAILED, "bad") }
        assertFalse(store.routines().single().enabled)
        assertEquals(10, store.routineRuns("r").size)
        assertFails { store.startRoutineRun("r", true, due) }
        val enabled = store.saveRoutine(store.routines().single().copy(enabled = true), due.plusSeconds(7200))
        assertEquals("2026-01-01T02:30:00Z", enabled.nextRunAt)
        store.close()
        store = LocalStore(context)
        assertEquals(10, store.routineRuns("r").size)
        store.deleteRoutine("r")
        assertTrue(store.routineRuns("r").isEmpty())
    }

    @Test fun `routine caps and conversation membership are checked transactionally`() {
        val channel = store.newConversation(store.bots().single().id)
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val template = LocalRoutine("r", channel.botId, channel.id, "Work", cron = "0 9 * * *", nextRunAt = now.toString(), createdAt = now.toString())
        assertFails { store.saveRoutine(template.copy(channelId = "missing"), now) }
        assertFails { store.saveRoutine(template.copy(instruction = "🙂".repeat(2001)), now) }
        assertFails { store.saveRoutine(template.copy(cron = "* * * * *"), now) }
        repeat(20) { store.saveRoutine(template.copy(id = "r$it"), now) }
        assertFails { store.saveRoutine(template, now) }
        assertEquals(20, store.routines().size)
        store.saveRoutine(store.routines().first().copy(enabled = false), now)
        store.saveRoutine(template, now)
        assertEquals(20, store.routines().count { it.enabled })
    }

    @Test fun `MCP tool grants stay tied to existing Bots and individual discovered tool names`() {
        val bot = store.bots().single()

        fun tool(name: String) = McpTool(name, inputSchema = kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) })
        val connection = LocalConnection("server", "Calendar", "https://example.com/mcp", tools = listOf(tool("read"), tool("write")), updatedAt = "t")
        store.saveConnection(connection.copy(grants = mapOf(bot.id to listOf("read"))))
        assertEquals(listOf("read"), store.connections().single().grants[bot.id])
        assertFails { store.saveConnection(connection.copy(grants = mapOf("other-bot" to listOf("read")))) }
        assertFails { store.saveConnection(connection.copy(grants = mapOf(bot.id to listOf("unknown-tool")))) }
        store.saveConnection(store.connections().single().copy(tools = connection.tools + tool("new-tool")))
        assertEquals(listOf("read"), store.connections().single().grants[bot.id])
        store.deleteBot(bot.id)
        assertTrue(
            store
                .connections()
                .single()
                .grants
                .isEmpty(),
        )
        store.close()
        store = LocalStore(context)
        assertEquals(
            3,
            store
                .connections()
                .single()
                .tools.size,
        )
        store.deleteConnection(connection.id)
        assertTrue(store.connections().isEmpty())
    }
}

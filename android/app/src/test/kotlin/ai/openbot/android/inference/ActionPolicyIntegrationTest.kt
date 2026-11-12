package ai.openbot.android.inference

import ai.openbot.android.data.*
import ai.openbot.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionPolicyIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val config =
        object : ModelConfiguration {
            override fun settings() = ProviderSettings()

            override fun hasKey() = true

            override fun apiKey() = "fixture-key"
        }

    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject

    @BeforeTest fun reset() {
        context.deleteDatabase("openbot.db")
    }

    @AfterTest fun cleanup() {
        context.deleteDatabase("openbot.db")
    }

    private fun setup(
        store: LocalStore,
        name: String = "write_note",
    ): Pair<LocalConversation, McpTool> {
        val channel = store.newConversation(store.bots().single().id)
        val tool = McpTool(name, "An action", obj("""{"type":"object","properties":{}}"""))
        store.saveConnection(LocalConnection("notes", "Notes", "https://example.com/mcp", tools = listOf(tool), grants = mapOf(channel.botId to listOf(name)), updatedAt = Instant.now().toString()))
        return channel to tool
    }

    private fun callReply(name: String) = obj("""{"status":"completed","output":[{"type":"function_call","call_id":"call","name":"${mcpModelToolName("notes",name)}","arguments":"{}"}]}""")

    private fun textReply(text: String) =
        buildJsonObject {
            put("status", "completed")
            putJsonArray("output") {
                addJsonObject {
                    put("type", "message")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "output_text")
                            put("text", text)
                        }
                    }
                }
            }
        }

    private fun session(
        onInitialize: () -> Unit = {},
        onCall: () -> Unit,
    ) = object : McpSession {
        override suspend fun initialize(): String {
            onInitialize()
            return "Notes"
        }

        override suspend fun listTools() = emptyList<McpTool>()

        override suspend fun call(
            tool: McpTool,
            args: JsonObject,
        ): JsonObject {
            onCall()
            return obj("""{"content":[{"type":"text","text":"Done"}]}""")
        }

        override suspend fun close() {}
    }

    @Test fun `personal default permits ordinary granted changes and records the actual result`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val(channel, tool) = setup(store)
                val model = LocalModel(context)
                var main = 0
                var calls = 0
                try {
                    assertFalse(ApprovalRuleStore(store).preferences().enabled)
                    val runtime = AgentRuntime(context, store, model, config, ProviderTransport { _, _, _ -> if (main++ == 0)callReply(tool.name)else textReply("Finished") }, { _, _ -> session { calls++ } })
                    assertEquals(TurnOutcome.COMPLETED, runtime.turn(channel.id, "Update my note"))
                    assertEquals(1, calls)
                    assertEquals(2, main)
                    assertTrue(store.pendingActions().single().automatic)
                    assertEquals(LocalActionStatus.EXECUTED, store.pendingActions().single().status)
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `enabling ask first during connection initialization stops an automatically admitted change`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val(channel, tool) = setup(store)
                val model = LocalModel(context)
                var main = 0
                var calls = 0
                try {
                    val runtime = AgentRuntime(context, store, model, config, ProviderTransport { _, _, _ -> if (main++ == 0)callReply(tool.name)else textReply("It was not done") }, { _, _ -> session(onInitialize = { ApprovalRuleStore(store).savePreferences(ApprovalPreferences(true, false)) }) { calls++ } })
                    runtime.turn(channel.id, "Update my note")
                    assertEquals(0, calls)
                    assertEquals(LocalActionStatus.CANCELLED, store.pendingActions().single().status)
                    assertEquals(NOT_DONE, store.toolEvents(channel.id).single().result)
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `pausing a goal during initialization prevents an automatic connector action`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val (channel, tool) = setup(store)
                val goals = GoalStore(store)
                val now = Instant.now().toString()
                goals.save(LocalGoal("goal", channel.botId, channel.id, "Brief", "Write a brief", "Has sources", createdAt = now, updatedAt = now))
                val run = assertNotNull(goals.claim(goals.enqueue("goal").id))
                val model = LocalModel(context)
                var requests = 0
                var calls = 0
                try {
                    val runtime =
                        AgentRuntime(
                            context,
                            store,
                            model,
                            config,
                            ProviderTransport { _, _, _ -> if (requests++ == 0) callReply(tool.name) else textReply("The action was not done") },
                            { _, _ -> session(onInitialize = { goals.transition("goal", GoalStatus.PAUSED) }) { calls++ } },
                            enqueueGoals = {},
                        )
                    runtime.turn(channel.id, "Write my brief", goalRunId = run.id)
                    assertEquals(0, calls)
                    assertEquals(run.id, store.pendingActions().single().goalRunId)
                    assertEquals(LocalActionStatus.CANCELLED, store.pendingActions().single().status)
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `pausing a routine during initialization prevents an automatic connector action`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val (channel, tool) = setup(store)
                val now = Instant.now().toString()
                val routine = store.saveRoutine(LocalRoutine("routine", channel.botId, channel.id, "Update note", cron = "0 * * * *", nextRunAt = now, createdAt = now))
                val run = assertNotNull(store.startRoutineRun(routine.id, manual = true))
                val model = LocalModel(context)
                var requests = 0
                var calls = 0
                try {
                    val runtime =
                        AgentRuntime(
                            context,
                            store,
                            model,
                            config,
                            ProviderTransport { _, _, _ -> if (requests++ == 0) callReply(tool.name) else textReply("The action was not done") },
                            { _, _ -> session(onInitialize = { store.saveRoutine(routine.copy(enabled = false)) }) { calls++ } },
                        )
                    runtime.turn(channel.id, "Update my note", routineRunId = run.id)
                    assertEquals(0, calls)
                    assertEquals(run.id, store.pendingActions().single().routineRunId)
                    assertEquals(LocalActionStatus.CANCELLED, store.pendingActions().single().status)
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `mandatory hand off rejects approval and never invokes a connector when marked handled`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val(channel, tool) = setup(store, "transfer_money")
                val model = LocalModel(context)
                var main = 0
                var calls = 0
                try {
                    val runtime =
                        AgentRuntime(
                            context,
                            store,
                            model,
                            config,
                            ProviderTransport { _, _, request ->
                                if (main++ == 0) {
                                    callReply(tool.name)
                                } else {
                                    assertEquals(
                                        HANDLED_BY_PERSON,
                                        request["input"]!!
                                            .jsonArray
                                            .last()
                                            .jsonObject["output"]!!
                                            .jsonPrimitive.content,
                                    )
                                    textReply("You did it yourself")
                                }
                            },
                            { _, _ -> session { calls++ } },
                        )
                    assertEquals(TurnOutcome.WAITING, runtime.turn(channel.id, "Perform this transfer"))
                    val action = store.pendingActions().single()
                    assertEquals(PolicyBehaviour.HAND_OFF, action.policy!!.behaviour)
                    assertFails { store.decideAction(action.id, LocalActionStatus.APPROVED) }
                    assertFails { store.decideAction(action.id, LocalActionStatus.APPROVED, always = true) }
                    store.decideAction(action.id, LocalActionStatus.HANDLED)
                    runtime.resume(action.turnId)
                    assertEquals(0, calls)
                    assertEquals(LocalActionStatus.HANDLED, store.pendingActions().single().status)
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `malformed auto review asks instead of executing and never offers tools to the reviewer`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val(channel, tool) = setup(store)
                ApprovalRuleStore(store).savePreferences(ApprovalPreferences(false, true))
                val model = LocalModel(context)
                var main = 0
                var reviews = 0
                var calls = 0
                try {
                    val runtime =
                        AgentRuntime(
                            context,
                            store,
                            model,
                            config,
                            ProviderTransport { _, _, request ->
                                if (request.containsKey("tools")) {
                                    main++
                                    callReply(tool.name)
                                } else {
                                    reviews++
                                    textReply("This is not a verdict object")
                                }
                            },
                            { _, _ -> session { calls++ } },
                        )
                    assertEquals(TurnOutcome.WAITING, runtime.turn(channel.id, "Update my note"))
                    assertEquals(1, main)
                    assertEquals(1, reviews)
                    assertEquals(0, calls)
                    assertTrue(
                        store
                            .pendingActions()
                            .single()
                            .policy!!
                            .review!!
                            .failedClosed,
                    )
                    assertEquals(
                        "auto_review",
                        store
                            .pendingActions()
                            .single()
                            .policy!!
                            .source,
                    )
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `always allow creates a scoped rule and later ordinary calls do not ask again`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val(channel, tool) = setup(store)
                val rules = ApprovalRuleStore(store)
                rules.savePreferences(ApprovalPreferences(true, false))
                val model = LocalModel(context)
                var main = 0
                var calls = 0
                try {
                    val runtime = AgentRuntime(context, store, model, config, ProviderTransport { _, _, _ -> if (main++ % 2 == 0)callReply(tool.name)else textReply("Finished") }, { _, _ -> session { calls++ } })
                    assertEquals(TurnOutcome.WAITING, runtime.turn(channel.id, "Update my note"))
                    val action = store.pendingActions().single()
                    store.decideAction(action.id, LocalActionStatus.APPROVED, always = true)
                    assertEquals(1, rules.rules().size)
                    assertEquals("notes", rules.rules().single().scope)
                    runtime.resume(action.turnId)
                    assertEquals(TurnOutcome.COMPLETED, runtime.turn(channel.id, "Update my note again"))
                    assertEquals(2, calls)
                    assertEquals(4, main)
                    assertTrue(store.pendingActions().last().automatic)
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `removing the standing rule before consumption cancels the saved approval`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val(channel, tool) = setup(store)
                val rules = ApprovalRuleStore(store)
                rules.savePreferences(ApprovalPreferences(true, false))
                val model = LocalModel(context)
                var main = 0
                var calls = 0
                try {
                    val runtime = AgentRuntime(context, store, model, config, ProviderTransport { _, _, _ -> if (main++ == 0)callReply(tool.name)else textReply("Not done") }, { _, _ -> session { calls++ } })
                    runtime.turn(channel.id, "Update my note")
                    val action = store.pendingActions().single()
                    store.decideAction(action.id, LocalActionStatus.APPROVED, always = true)
                    rules.delete(rules.rules().single().id)
                    runtime.resume(action.turnId)
                    assertEquals(0, calls)
                    assertEquals(LocalActionStatus.CANCELLED, store.pendingActions().single().status)
                } finally {
                    model.close()
                }
            }
        }

    @Test fun `a deleting action asks even with allow rules and cannot create an always allow rule`() =
        runBlocking<Unit> {
            LocalStore(context).use { store ->
                val(channel, tool) = setup(store, "delete_note")
                val rules = ApprovalRuleStore(store)
                rules.save(ApprovalRule("allow", "*", "*", "*", "*", RuleBehaviour.ALLOW))
                val model = LocalModel(context)
                var main = 0
                var calls = 0
                try {
                    val runtime = AgentRuntime(context, store, model, config, ProviderTransport { _, _, _ -> if (main++ == 0)callReply(tool.name)else textReply("Finished") }, { _, _ -> session { calls++ } })
                    assertEquals(TurnOutcome.WAITING, runtime.turn(channel.id, "Delete the note"))
                    val action = store.pendingActions().single()
                    assertEquals("safety", action.policy!!.source)
                    assertFails { store.decideAction(action.id, LocalActionStatus.APPROVED, always = true) }
                    assertEquals(1, rules.rules().size)
                    store.decideAction(action.id, LocalActionStatus.APPROVED)
                    runtime.resume(action.turnId)
                    assertEquals(1, calls)
                } finally {
                    model.close()
                }
            }
        }
}

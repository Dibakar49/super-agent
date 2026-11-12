package ai.openbot.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import ai.openbot.android.data.ModelSettingsStore
import ai.openbot.android.data.LocalStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],qualifiers="w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalAppJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    private fun navigate(destination:String) {
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Show sidebar").assertExists()}.isSuccess}
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.onNode(hasText(destination) and hasClickAction()).performClick()
    }
    private fun readyMessage() {
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Message").assertIsEnabled()}.isSuccess}
    }

    @Test fun `source home drawer bot memory and draft flow`() {
        readyMessage()
        compose.onNodeWithText("Start a conversation").assertIsDisplayed()
        compose.onNodeWithText("Explore Bots").assertIsDisplayed()
        capture("native-home")
        navigate("Bots")
        compose.onNodeWithText("+ New Bot").performClick()
        compose.onNodeWithText("Name").performTextInput("Planner")
        compose.onNodeWithText("Instructions").performTextInput("Help plan my day.")
        compose.onNodeWithText("Create Bot").performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("bots-list").performScrollToNode(hasText("Planner"))}.isSuccess}
        compose.onNodeWithText("Planner").assertIsDisplayed()
        navigate("Capabilities")
        compose.onNodeWithText("Memory").performClick()
        compose.onNodeWithText("+ Remember a fact").performClick()
        compose.onNodeWithText("Something you want your Bots to know").performTextInput("I prefer concise answers.")
        compose.onNodeWithText("Remember",substring=false).performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("memory-content").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("memory-content").assertTextEquals("I prefer concise answers.")
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Use this memory",useUnmergedTree=true).assertIsEnabled()}.isSuccess}
        compose.onNodeWithContentDescription("Use this memory",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Added on this device · Turned off").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Edit").performClick()
        compose.onNode(hasSetTextAction() and hasText("Memory")).performTextReplacement("I prefer short answers.")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithTag("memory-content").assertTextEquals("I prefer short answers.")}.isSuccess}
        capture("native-memory")
        navigate("Bots")
        compose.onNodeWithTag("bots-list").performScrollToNode(hasText("Planner"))
        compose.onNode(hasText("Planner") and hasClickAction()).performClick()
        compose.onNodeWithTag("bot-profile").performScrollToNode(hasText("Message Planner"))
        compose.onNode(hasText("Message Planner") and hasClickAction()).performClick()
        readyMessage()
        compose.onNodeWithContentDescription("Message").performTextInput("Hello")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Add your model provider key in Model settings first.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Hello").assertExists()
        LocalStore(compose.activity).use{assertTrue(it.conversations().isEmpty(),"Failed first send must not create an empty history entry.")}
    }

    @Test fun `model settings reject HTTP and persist a valid provider configuration`() {
        readyMessage();navigate("Settings")
        compose.onNodeWithText("Model settings").performScrollTo().performClick()
        compose.onNodeWithText("HTTPS model API URL").performTextReplacement("http://example.com/v1")
        compose.onNodeWithTag("model-settings-list").performScrollToNode(hasText("Save model settings"))
        compose.onNodeWithText("Save model settings").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Use an HTTPS model API URL without credentials, query parameters, or a fragment.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithTag("model-settings-list").performScrollToNode(hasText("HTTPS model API URL"))
        compose.onNodeWithText("HTTPS model API URL").performTextReplacement("https://example.com/v1")
        compose.onNodeWithText("Model name").performTextReplacement("test-model")
        compose.onNodeWithTag("model-settings-list").performScrollToNode(hasText("Save model settings"))
        compose.waitUntil(10_000){runCatching{compose.onNodeWithText("Save model settings").assertIsEnabled()}.isSuccess}
        compose.onNodeWithText("Save model settings").performScrollTo().assertIsDisplayed().performClick()
        try {compose.waitUntil(10_000){compose.onAllNodesWithText("Model settings saved.").fetchSemanticsNodes().isNotEmpty()}}
        catch(failure:Throwable){capture("native-model-save-failure");File(System.getProperty("user.home"),"model-save-semantics.txt").writeText(compose.onRoot().printToString());throw failure}
        assertEquals("test-model",ModelSettingsStore(compose.activity).settings().model)
        assertEquals("https://example.com/v1",ModelSettingsStore(compose.activity).settings().baseUrl)
        assertFalse(ModelSettingsStore(compose.activity).hasKey())
    }

    @Test fun `skill editor persists the original command and instructions`() {
        readyMessage();navigate("Capabilities")
        compose.onNodeWithText("+ New skill").performClick()
        compose.onNodeWithText("Command").performTextInput("standup")
        compose.onNodeWithText("Title").performTextInput("Standup notes")
        compose.onNodeWithText("One-liner").performTextInput("Prepare a standup update")
        compose.onNodeWithText("Instructions").performTextInput("Summarise the work, then list what is left.")
        compose.onNodeWithText("Save skill").performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("skill-standup").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Standup notes").assertIsDisplayed()
        LocalStore(compose.activity).use{assertEquals("Summarise the work, then list what is left.",it.skills().single().instructions)}
        capture("native-skills")
    }

    @Test fun `routine page switches persists history and deletes with source confirmation`() {
        readyMessage()
        LocalStore(compose.activity).use{store->
            val channel=store.newConversation(store.bots().single().id)
            val now=java.time.Instant.now().toString()
            store.saveRoutine(ai.openbot.core.LocalRoutine("morning",channel.botId,channel.id,"Summarise the day","Morning briefing","0 9 * * *","UTC",nextRunAt=now,createdAt=now))
        }
        navigate("Routines")
        compose.waitUntil(10_000){compose.onAllNodesWithText("Morning briefing").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Every day at 09:00 · UTC").assertExists()
        compose.onNodeWithContentDescription("Enable the routine scheduled Every day at 09:00",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Paused").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Run now").assertIsNotEnabled()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("No runs yet.").assertExists()
        capture("native-routines")
        compose.onNodeWithText("Delete",substring=false).performClick()
        compose.onNodeWithText("This routine stops for good. You can't undo this.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-delete-routine").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("No routines yet").fetchSemanticsNodes().isNotEmpty()}
        LocalStore(compose.activity).use{assertTrue(it.routines().isEmpty())}
    }

    @Test fun `MCP connection grants and disconnect work from the native sidebar`() {
        readyMessage()
        var botName=""
        LocalStore(compose.activity).use{store->
            val bot=store.bots().single();botName=bot.name
            val schema=kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"object","properties":{}}""") as kotlinx.serialization.json.JsonObject
            store.saveConnection(ai.openbot.core.LocalConnection("notes","Notes","https://example.com/mcp",tools=listOf(ai.openbot.core.McpTool("read_notes","Read your notes",schema)),updatedAt=java.time.Instant.now().toString()))
        }
        navigate("Connections")
        compose.waitUntil(10_000){compose.onAllNodesWithText("Notes").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("Notes") and hasClickAction()).performClick()
        compose.onNode(hasText(botName) and hasClickAction() and hasAnyAncestor(hasTestTag("connection-profile"))).performClick()
        compose.onNodeWithContentDescription("Allow $botName to use read_notes",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Allow $botName to use read_notes",useUnmergedTree=true).assertIsOn()}.isSuccess}
        capture("native-connections")
        compose.onNodeWithTag("connection-profile").performScrollToNode(hasText("Disconnect"))
        compose.onNodeWithText("Disconnect",substring=false).performClick()
        compose.onNodeWithTag("confirm-disconnect").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("No apps yet").fetchSemanticsNodes().isNotEmpty()}
        LocalStore(compose.activity).use{assertTrue(it.connections().isEmpty())}
    }

    @Test fun `a pending approval is visible and denial is saved without executing a tool`() {
        readyMessage()
        LocalStore(compose.activity).use{store->
            val bot=store.bots().single();val channel=store.newConversation(bot.id);val now=java.time.Instant.now().toString()
            val schema=kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}}}""") as kotlinx.serialization.json.JsonObject
            val tool=ai.openbot.core.McpTool("write_note","Write a note",schema)
            store.saveConnection(ai.openbot.core.LocalConnection("notes","Notes","https://example.com/mcp",tools=listOf(tool),grants=mapOf(bot.id to listOf(tool.name)),updatedAt=now))
            val user=ai.openbot.core.LocalMessage("question",channel.id,"user","Save a note",now)
            val pending=ai.openbot.core.LocalMessage("answer",channel.id,"assistant","Working…",now,ai.openbot.core.MessageState.GENERATING)
            store.beginTurn(user,pending)
            val args=kotlinx.serialization.json.Json.parseToJsonElement("""{"text":"Remember this"}""") as kotlinx.serialization.json.JsonObject
            val call=ai.openbot.core.ToolCall("call",ai.openbot.core.mcpModelToolName("notes",tool.name),args)
            val round=ai.openbot.core.AgentRound("",listOf(call),emptyList())
            val action=ai.openbot.core.PendingLocalAction("approval",pending.id,channel.id,bot.id,"notes","https://example.com/mcp",null,tool,call,now)
            store.waitForApproval(ai.openbot.core.AgentCheckpoint(pending.id,channel.id,bot.id,ai.openbot.core.ProviderSettings(),emptyList(),kotlinx.serialization.json.JsonArray(emptyList()),round,emptyList(),action.id,now),action)
        }
        navigate("Approvals")
        compose.waitUntil(10_000){compose.onAllNodesWithTag("approval-approval").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Allow once").assertIsDisplayed()
        capture("native-approvals")
        compose.onNodeWithText("Deny",substring=false).performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Nothing waiting for you").fetchSemanticsNodes().isNotEmpty()}
        LocalStore(compose.activity).use{store->assertEquals(ai.openbot.core.LocalActionStatus.DENIED,store.pendingActions().single().status);assertTrue(store.toolEvents(store.conversations().single().id).isEmpty())}
    }

    @Test fun `goal creation pause resume and completion work in the Bot profile`() {
        readyMessage()
        LocalStore(compose.activity).use{store->
            val channel=store.newConversation(store.bots().single().id)
            store.saveConversation(channel.copy(summary="Daily planning"))
        }
        navigate("Bots")
        compose.onNode(hasText("OpenBot") and hasClickAction() and hasAnyAncestor(hasTestTag("bots-list"))).performClick()
        compose.onNodeWithTag("bot-profile").performScrollToNode(hasText("Goals"))
        compose.onNode(hasText("Goals") and hasClickAction()).performClick()
        compose.onNodeWithText("+ New goal").performClick()
        compose.onNode(hasText("Choose a conversation") and hasClickAction()).performClick()
        compose.onNode(hasText("Daily planning") and hasClickAction() and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Title").performTextInput("Weekly brief")
        compose.onNodeWithText("Instruction").performTextInput("Summarise the completed work.")
        compose.onNodeWithText("Success criteria").performTextInput("A concise summary with evidence.")
        compose.onNodeWithTag("save-goal").performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Weekly brief").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("Weekly brief") and hasClickAction() and hasAnyAncestor(hasTestTag("goals-list"))).performClick()
        compose.onNodeWithContentDescription("Goal active",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Paused · Nothing starts it until you resume it").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Goal active",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Goal active",useUnmergedTree=true).assertIsOn()}.isSuccess}
        compose.onNodeWithText("Complete",substring=false).performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Completed",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Goal active",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Cancel",substring=false).performScrollTo().performClick()
        capture("native-goals")
        LocalStore(compose.activity).use{store->assertEquals(ai.openbot.core.GoalStatus.COMPLETED,ai.openbot.android.data.GoalStore(store).list().single().status)}
    }

    @Test fun `approval settings and custom rule behaviour persist through native controls`() {
        readyMessage();navigate("Settings")
        compose.onNodeWithTag("settings-approvals").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Always ask before making changes",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Always ask before making changes",useUnmergedTree=true).assertIsOn()}.isSuccess}
        compose.onNodeWithTag("approval-rules-list").performScrollToNode(hasText("Add a rule"))
        compose.onNode(hasText("Add a rule") and hasClickAction()).performClick()
        compose.onNodeWithText("Tool or app").performTextInput("*")
        compose.onNodeWithTag("save-rule").performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("Behaviour for Any tool").fetchSemanticsNodes().isNotEmpty()}
        val originalId=LocalStore(compose.activity).use{ai.openbot.android.data.ApprovalRuleStore(it).rules().single().id}
        compose.onNodeWithContentDescription("Behaviour for Any tool").performScrollTo().performClick()
        compose.onNode(hasText("Take action without asking") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Take action without asking").fetchSemanticsNodes().isNotEmpty()}
        LocalStore(compose.activity).use{store->
            val rule=ai.openbot.android.data.ApprovalRuleStore(store).rules().single()
            assertEquals(originalId,rule.id);assertEquals(ai.openbot.core.RuleBehaviour.ALLOW,rule.behaviour)
        }
        capture("native-approval-rules")
        compose.onNodeWithText("Remove",substring=false).performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("No rules yet").fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun `questionnaire accepts choices and personal answers and queues one continuation`() {
        readyMessage()
        LocalStore(compose.activity).use{store->
            val bot=store.bots().single();val channel=store.newConversation(bot.id);val now=java.time.Instant.now().toString()
            store.beginTurn(ai.openbot.core.LocalMessage("question-user",channel.id,"user","Plan my weekend",now),
                ai.openbot.core.LocalMessage("question-turn",channel.id,"assistant","Working…",now,ai.openbot.core.MessageState.GENERATING))
            val args=kotlinx.serialization.json.Json.parseToJsonElement("""{"question":"Where and when?","parts":[{"header":"City","question":"Which city?","options":["Kolkata","Delhi"]},{"header":"Day","question":"Which day?"}]}""") as kotlinx.serialization.json.JsonObject
            val call=ai.openbot.core.ToolCall("q-call","ask_person",args)
            val (text,why,parts)=ai.openbot.core.parseQuestion(call)
            val question=ai.openbot.core.LocalQuestion("weekend-question","question-turn",channel.id,bot.id,call,text,why,parts,now)
            val round=ai.openbot.core.AgentRound("",listOf(call),emptyList())
            ai.openbot.android.data.QuestionStore(store).wait(ai.openbot.core.AgentCheckpoint("question-turn",channel.id,bot.id,ai.openbot.core.ProviderSettings(),listOf(ai.openbot.core.askPersonTool),kotlinx.serialization.json.JsonArray(emptyList()),round,emptyList(),"",now,questionId=question.id),question)
        }
        navigate("Approvals")
        compose.waitUntil(10_000){compose.onAllNodesWithTag("question-weekend-question").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Next",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("Kolkata",substring=false).performClick()
        compose.onNodeWithText("Next",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("question-answer").performTextInput("Saturday")
        compose.onNode(hasText("Back") and hasAnyAncestor(hasTestTag("question-weekend-question"))).performClick()
        compose.onNodeWithText("Kolkata",substring=false).assertIsDisplayed()
        compose.onNodeWithText("Next",substring=false).performClick()
        compose.onNodeWithTag("question-answer").assertTextContains("Saturday")
        capture("native-questions")
        compose.onNodeWithText("Send answers",substring=false).performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Nothing waiting for you").fetchSemanticsNodes().isNotEmpty()}
        LocalStore(compose.activity).use{store->
            val question=ai.openbot.android.data.QuestionStore(store).list().single()
            assertEquals("City: Kolkata\nDay: Saturday",question.answer)
            assertEquals(ai.openbot.core.ContinuationPhase.QUEUED,store.checkpoints().single().phase)
            assertEquals(1,store.toolEvents(question.channelId).size)
        }
    }

    @Test fun `conversation drafts and settings edits survive navigation independently`() {
        readyMessage()
        LocalStore(compose.activity).use{store->
            val bot=store.bots().single()
            store.newConversation(bot.id).let{store.saveConversation(it.copy(summary="First thread"))}
            store.newConversation(bot.id).let{store.saveConversation(it.copy(summary="Second thread"))}
        }
        navigate("First thread")
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Message").assertIsEnabled()}.isSuccess}
        compose.onNodeWithContentDescription("Message").performTextInput("Draft for first")
        navigate("Second thread")
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Message").assertTextContains("Ask anything")}.isSuccess}
        compose.onNodeWithContentDescription("Message").performTextInput("Draft for second")
        navigate("First thread")
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Message").assertTextContains("Draft for first")}.isSuccess}
        navigate("Second thread")
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Message").assertTextContains("Draft for second")}.isSuccess}
        navigate("Settings")
        compose.onNodeWithText("Name",substring=false).performTextReplacement("Unsaved name")
        compose.onNodeWithText("Model settings",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Back",substring=false).performClick()
        compose.onNodeWithText("Name",substring=false).performScrollTo().assertTextContains("Unsaved name")
        LocalStore(compose.activity).use{assertNotEquals("Unsaved name",it.preferences().name)}
    }

    @Test fun `dialog scrim covers navigation and hides underlying accessibility`() {
        readyMessage();navigate("Capabilities")
        compose.onNodeWithText("Memory",substring=false).performClick()
        compose.onNodeWithText("+ Remember a fact").performClick()
        compose.onNodeWithContentDescription("Close dialog").assertExists()
        compose.onNodeWithContentDescription("Show sidebar").assertDoesNotExist()
        capture("native-memory-dialog")
        compose.onRoot(useUnmergedTree=true).performTouchInput {click(androidx.compose.ui.geometry.Offset(30f,28f))}
        compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("Close dialog").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithContentDescription("Show sidebar").assertIsDisplayed()
        compose.onNodeWithText("+ Remember a fact").assertIsDisplayed()
        LocalStore(compose.activity).use{assertTrue(it.memories().isEmpty())}
    }

    @Test fun `sidebar pins a conversation and confirms deletion before removing its messages`() {
        readyMessage()
        LocalStore(compose.activity).use{store->
            val channel=store.newConversation(store.bots().single().id);val now=java.time.Instant.now().toString()
            store.saveMessage(ai.openbot.core.LocalMessage("delete-user",channel.id,"user","Hello",now))
            store.saveConversation(store.conversations().single().copy(summary="Review thread"))
        }
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Review thread",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Review thread",substring=false).performTouchInput{longClick()}
        compose.onNode(hasText("Pin conversation") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(10_000){LocalStore(compose.activity).use{it.conversations().single().pinned}}
        compose.onNodeWithText("Review thread",substring=false).performTouchInput{longClick()}
        compose.onNode(hasText("Delete conversation…") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("It disappears for everyone in the conversation.").assertIsDisplayed()
        compose.onNodeWithText("Cancel",substring=false).performClick()
        LocalStore(compose.activity).use{assertEquals(1,it.conversations().size)}
        compose.onNodeWithText("Review thread",substring=false).performTouchInput{longClick()}
        compose.onNode(hasText("Delete conversation…") and hasAnyAncestor(isPopup())).performClick()
        capture("native-delete-conversation")
        compose.onNodeWithTag("confirm-delete-conversation").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("No conversations yet").fetchSemanticsNodes().isNotEmpty()}
        LocalStore(compose.activity).use{assertTrue(it.conversations().isEmpty());assertEquals(1,it.bots().size)}
    }

    @Test fun `notification link opens the matching question and rejects unrelated URI routes`() {
        readyMessage()
        var targetChannel=""
        LocalStore(compose.activity).use{store->
            val bot=store.bots().single();val now=java.time.Instant.now().toString()
            for(index in 1..2) {
                val channel=store.newConversation(bot.id);if(index==2)targetChannel=channel.id
                store.beginTurn(ai.openbot.core.LocalMessage("link-u$index",channel.id,"user","Plan $index",now),ai.openbot.core.LocalMessage("link-a$index",channel.id,"assistant","Working…",now,ai.openbot.core.MessageState.GENERATING))
                val call=ai.openbot.core.ToolCall("link-call$index","ask_person",kotlinx.serialization.json.buildJsonObject {put("question",kotlinx.serialization.json.JsonPrimitive("Question $index?"))})
                val question=ai.openbot.core.LocalQuestion("link-q$index","link-a$index",channel.id,bot.id,call,"Question $index?",createdAt=now)
                ai.openbot.android.data.QuestionStore(store).wait(ai.openbot.core.AgentCheckpoint(question.turnId,channel.id,bot.id,ai.openbot.core.ProviderSettings(),listOf(ai.openbot.core.askPersonTool),kotlinx.serialization.json.JsonArray(emptyList()),ai.openbot.core.AgentRound("",listOf(call),emptyList()),emptyList(),"",now,questionId=question.id),question)
            }
        }
        compose.runOnIdle {newIntent(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("openbotmobile://approvals?channelId=$targetChannel&requestId=link-q2")))}
        compose.waitUntil(10_000){compose.onAllNodesWithText("Question 2?",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Question 1?",substring=false).assertDoesNotExist()
        compose.runOnIdle {newIntent(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://example.com/conversation?channelId=$targetChannel")))}
        compose.onNodeWithText("Question 2?",substring=false).assertExists()
        navigate("Approvals")
        compose.waitUntil(10_000){compose.onAllNodesWithText("Question 1?",substring=false).fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun `Android file picker stages removable files and preserves them when sending is refused`() {
        readyMessage()
        val input=File(compose.activity.filesDir,"attachments/picker-input.txt")
        input.parentFile!!.mkdirs();input.writeText("Planning notes")
        val uri=androidx.core.content.FileProvider.getUriForFile(compose.activity,compose.activity.packageName+".files",input,"planning.txt")
        fun pick() {
            compose.onNodeWithContentDescription("Attach files").performClick()
            compose.runOnIdle {
                val request=org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivityForResult
                assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,request.intent.action)
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any?>(compose.activity,"onActivityResult",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!,request.requestCode),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!,android.app.Activity.RESULT_OK),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.Intent::class.java,android.content.Intent().setData(uri)))
            }
            compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("Remove planning.txt").fetchSemanticsNodes().isNotEmpty()}
        }
        pick()
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
        LocalStore(compose.activity).use{store->
            val file=ai.openbot.android.data.AttachmentStore(compose.activity,store).staged("home").single()
            assertEquals("planning.txt",file.name);assertEquals("text/plain",file.mimeType)
            assertEquals("Planning notes",File(compose.activity.filesDir,"attachments/${file.id}").readText())
        }
        capture("native-attachments")
        compose.onNodeWithContentDescription("Remove planning.txt").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("Remove planning.txt").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        pick()
        navigate("Settings")
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.onNodeWithContentDescription("New conversation").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("Remove planning.txt").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Add your model provider key in Model settings first.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Remove planning.txt").assertExists()
        LocalStore(compose.activity).use{store->assertTrue(store.conversations().isEmpty());assertEquals(1,ai.openbot.android.data.AttachmentStore(compose.activity,store).staged("home").size)}
    }

    @Test fun `dictation configuration and microphone denial preserve the draft`() {
        readyMessage();navigate("Settings")
        compose.onNodeWithText("Model settings",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("model-settings-list").performScrollToNode(hasText("Dictation settings"))
        compose.onNodeWithText("Dictation settings",substring=false).performClick()
        compose.onNodeWithText("HTTPS transcription API URL").performTextReplacement("https://voice.example/v1")
        compose.onNodeWithText("Transcription model",substring=false).performTextReplacement("speech-model")
        compose.onNodeWithText("Save dictation settings",substring=false).performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Dictation settings saved.").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(ai.openbot.android.data.TranscriptionSettingsStore(compose.activity).configured())
        capture("native-dictation-settings")
        compose.onNodeWithContentDescription("Show sidebar").performClick()
        compose.onNodeWithContentDescription("New conversation").performClick()
        readyMessage();compose.onNodeWithContentDescription("Message").performTextInput("Keep this draft")
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithContentDescription("Dictate a message").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Waiting for microphone permission…").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {
            val request=org.robolectric.Shadows.shadowOf(compose.activity).lastRequestedPermission
            assertTrue(request.requestedPermissions.contains(android.Manifest.permission.RECORD_AUDIO))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any?>(compose.activity,"onRequestPermissionsResult",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!,request.requestCode),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Array<String>::class.java,arrayOf(android.Manifest.permission.RECORD_AUDIO)),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(IntArray::class.java,intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED)))
        }
        compose.waitUntil(10_000){compose.onAllNodesWithText("Microphone access was denied. Allow it in Android settings and try again.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Record again",substring=false).assertExists()
        capture("native-dictation-denied")
        compose.onNodeWithContentDescription("Cancel dictation").performClick()
        compose.waitUntil(10_000){runCatching{compose.onNodeWithContentDescription("Message").assertTextContains("Keep this draft")}.isSuccess}
        LocalStore(compose.activity).use{assertTrue(it.conversations().isEmpty())}
    }

    private fun newIntent(intent:android.content.Intent) {
        val original=compose.activity.intent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any?>(compose.activity,"onNewIntent",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.content.Intent::class.java,android.content.Intent(original).setData(intent.data)))
        // ActivityScenario uses the original launch intent to recognize teardown callbacks.
        compose.activity.intent=original
    }

    private fun capture(name:String) {
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output=File(System.getProperty("user.home"),"screenshots/$name-api${Build.VERSION.SDK_INT}.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}

package ai.openbot.android.inference

import ai.openbot.android.data.LocalStore
import ai.openbot.core.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AgentRuntimeTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val configuration=object:ModelConfiguration {
        override fun settings()=ProviderSettings()
        override fun hasKey()=true
        override fun apiKey()="local-test-fixture"
    }
    private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
    @BeforeTest fun reset(){context.deleteDatabase("openbot.db")}
    @AfterTest fun cleanup(){context.deleteDatabase("openbot.db")}

    @Test fun `streamed text is durable while generating and survives cancellation`() = runBlocking<Unit> {
        LocalStore(context).use { store ->
            val channel = store.newConversation(store.bots().single().id)
            val model = LocalModel(context)
            val visible = CompletableDeferred<Unit>()
            val provider = object : ProviderTransport {
                override suspend fun request(settings: ProviderSettings, key: String, request: JsonObject): JsonObject = error("Streaming path expected")
                override suspend fun stream(settings: ProviderSettings, key: String, request: JsonObject, onText: suspend (String) -> Unit): JsonObject {
                    onText("First part of the answer")
                    visible.complete(Unit)
                    awaitCancellation()
                }
            }
            try {
                val job = launch { AgentRuntime(context, store, model, configuration, provider).turn(channel.id, "Hello") }
                visible.await()
                assertEquals(MessageState.GENERATING, store.messages(channel.id).last().state)
                assertEquals("First part of the answer", store.messages(channel.id).last().text)
                job.cancelAndJoin()
                val answer = store.messages(channel.id).last()
                assertEquals(MessageState.INTERRUPTED, answer.state)
                assertContains(answer.text, "First part of the answer")
                assertContains(answer.text, "Generation was interrupted.")
                assertTrue(store.toolEvents(channel.id).isEmpty())
            } finally { model.close() }
        }
    }

    @Test fun `a provider tool turn actually saves a routine and returns its result before the answer`()=runBlocking<Unit> {
        LocalStore(context).use {store->
            val model=LocalModel(context)
            try {
                val bot=store.bots().single();val channel=store.newConversation(bot.id)
                var requests=0;var accepted=0
                val transport=ProviderTransport{_,_,request->
                    requests++
                    if(requests==1) {
                        val tools=request["tools"]!!.jsonArray.map{it.jsonObject["name"]!!.jsonPrimitive.content}
                        assertContains(tools,"create_routine");assertContains(tools,"remember_personal_fact")
                        assertFalse(tools.any{it.contains("computer")})
                        obj("""{"status":"completed","output":[{"type":"function_call","id":"fc1","call_id":"call1","name":"create_routine","arguments":"{\"instruction\":\"Summarise the day\",\"cron\":\"0 9 * * *\",\"timezone\":\"UTC\"}"}]}""")
                    } else {
                        val output=request["input"]!!.jsonArray.last().jsonObject
                        assertEquals("call1",output["call_id"]!!.jsonPrimitive.content)
                        val saved=Json.parseToJsonElement(output["output"]!!.jsonPrimitive.content).jsonObject
                        assertEquals(channel.id,saved["channelId"]!!.jsonPrimitive.content)
                        assertEquals(1,store.routines().size)
                        obj("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"It will run every day at 09:00 UTC."}]}]}""")
                    }
                }
                AgentRuntime(context,store,model,configuration,transport).turn(channel.id,"Summarise the day every morning at 9 UTC",onAccepted={accepted++})
                assertEquals(2,requests);assertEquals(1,accepted)
                assertEquals("It will run every day at 09:00 UTC.",store.messages(channel.id).last().text)
                assertEquals(MessageState.COMPLETE,store.messages(channel.id).last().state)
            }finally {model.close()}
        }
    }

    @Test fun `learned facts are scoped deduplicated reviewable and remain forgotten`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val bot=store.bots().single();val channel=store.newConversation(bot.id)
            val model=LocalModel(context)
            try {
                val runtime=AgentRuntime(context,store,model)
                val args=obj("""{"content":"Prefers morning meetings","sourceApp":"Conversation"}""")
                val first=Json.parseToJsonElement(runtime.execute(ToolCall("c1","save_observed_memory",args),bot.id,channel.id)).jsonObject
                assertEquals(ReviewState.UNREVIEWED,store.memories().single().reviewState)
                runtime.execute(ToolCall("c2","save_observed_memory",args),bot.id,channel.id)
                assertEquals(1,store.memories().size)
                assertTrue(store.recallMemory("different-bot").isEmpty())
                val prompt=localPrompt(bot,store.memories(),listOf(LocalMessage("m",channel.id,"user","Hello",Instant.now().toString())))
                assertContains(prompt,"untrusted data");assertContains(prompt,"Prefers morning meetings")
                runtime.execute(ToolCall("c3","forget_personal_memory",buildJsonObject{put("id",first["id"]!!.jsonPrimitive.content)}),bot.id,channel.id)
                val forgotten=Json.parseToJsonElement(runtime.execute(ToolCall("c4","save_observed_memory",args),"different-bot",channel.id)).jsonObject
                assertEquals(true,forgotten["forgotten"]!!.jsonPrimitive.boolean)
                assertTrue(store.memories().isEmpty())
            }finally {model.close()}
        }
    }
    @Test fun `replayed tool identities reuse a committed result without duplicating effects`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val bot=store.bots().single();val channel=store.newConversation(bot.id)
            val model=LocalModel(context)
            try {
                val runtime=AgentRuntime(context,store,model)
                val call=ToolCall("c1","create_routine",obj("""{"instruction":"Work","cron":"0 9 * * *","timezone":"UTC"}"""))
                val first=runtime.executeRecorded(call,bot.id,channel.id,"turn")
                assertEquals(first,runtime.executeRecorded(call,bot.id,channel.id,"turn"))
                assertEquals(1,store.routines().size);assertEquals(1,store.toolEvents(channel.id).size)
                assertFails{runtime.executeRecorded(call.copy(arguments=obj("""{"instruction":"Different","cron":"0 9 * * *"}""")),bot.id,channel.id,"turn")}
                val error=runtime.executeRecorded(call.copy(id="c2",arguments=obj("""{"instruction":"Work","cron":"* * * * *"}""")),bot.id,channel.id,"turn")
                assertTrue(Json.parseToJsonElement(error).jsonObject["isError"]!!.jsonPrimitive.boolean)
                assertEquals(1,store.routines().size)
                assertTrue(store.toolEvents(channel.id).last().failed)
                store.deleteConversation(channel.id)
                assertTrue(store.toolEvents(channel.id).isEmpty())
            }finally{model.close()}
        }
    }

    @Test fun `stopping a turn records interruption and releases the local execution gate`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val channel=store.newConversation(store.bots().single().id)
            val model=LocalModel(context)
            val started=CompletableDeferred<Unit>()
            try {
                val runtime=AgentRuntime(context,store,model,configuration,ProviderTransport{_,_,_->started.complete(Unit);awaitCancellation()})
                val job=launch {runtime.turn(channel.id,"A question")}
                started.await()
                job.cancelAndJoin()
                assertEquals(listOf(MessageState.COMPLETE,MessageState.INTERRUPTED),store.messages(channel.id).map{it.state})
                assertFalse(AgentGate.mutex.isLocked)
                assertEquals("A question",store.messages(channel.id).first().text)
            }finally{model.close()}
        }
    }

}

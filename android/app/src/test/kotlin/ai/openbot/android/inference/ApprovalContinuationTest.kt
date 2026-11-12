package ai.openbot.android.inference

import ai.openbot.android.data.LocalStore
import ai.openbot.core.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ApprovalContinuationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val configuration=object:ModelConfiguration {
        override fun settings()=ProviderSettings()
        override fun hasKey()=true
        override fun apiKey()="local-fixture-key"
    }
    private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
    private fun tool()=McpTool("write_note","Write a note",obj("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""))
    @BeforeTest fun reset(){context.deleteDatabase("openbot.db")}
    @AfterTest fun cleanup(){context.deleteDatabase("openbot.db")}

    private class Session(private val onCall:()->Unit):McpSession {
        override suspend fun initialize()="Notes"
        override suspend fun listTools()=emptyList<McpTool>()
        override suspend fun call(tool:McpTool,args:JsonObject):JsonObject {
            onCall();assertEquals("Remember this",args["text"]!!.jsonPrimitive.content)
            return Json.parseToJsonElement("""{"content":[{"type":"text","text":"Saved note"}]}""").jsonObject
        }
        override suspend fun close(){}
    }
    private fun firstReply()=obj("""{"status":"completed","output":[{"type":"function_call","id":"fc1","call_id":"c1","name":"${mcpModelToolName("notes","write_note")}","arguments":"{\"text\":\"Remember this\"}"}]}""")
    private fun finalReply()=obj("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Finished."}]}]}""")
    private fun connect(store:LocalStore):LocalConversation {
        ai.openbot.android.data.ApprovalRuleStore(store).savePreferences(ApprovalPreferences(true,false))
        val bot=store.bots().single();val channel=store.newConversation(bot.id)
        store.saveConnection(LocalConnection("notes","Notes","https://example.com/mcp",tools=listOf(tool()),grants=mapOf(bot.id to listOf("write_note")),updatedAt=Instant.now().toString()))
        return channel
    }

    @Test fun `approval survives reopening and executes once before the provider continues`()=runBlocking<Unit> {
        var store=LocalStore(context);var model=LocalModel(context)
        try {
            val channel=connect(store);var requests=0;var calls=0
            val provider=ProviderTransport{_,_,request->
                requests++
                if(requests==1)firstReply() else {
                    assertContains(request["input"]!!.jsonArray.last().jsonObject["output"]!!.jsonPrimitive.content,"Saved note")
                    finalReply()
                }
            }
            val sessions={_:LocalConnection,_:String?->Session{calls++}}
            val outcome=AgentRuntime(context,store,model,configuration,provider,sessions).turn(channel.id,"Save a note")
            assertEquals(TurnOutcome.WAITING,outcome);assertEquals(0,calls);assertEquals(1,requests)
            assertEquals(MessageState.WAITING,store.messages(channel.id).last().state)
            val approval=store.pendingActions().single();val turnId=approval.turnId
            assertEquals(LocalActionStatus.PENDING,approval.status)
            store.close();model.close();store=LocalStore(context);model=LocalModel(context)
            store.decideAction(approval.id,LocalActionStatus.APPROVED)
            assertFails{store.decideAction(approval.id,LocalActionStatus.APPROVED)}
            val runtime=AgentRuntime(context,store,model,configuration,provider,sessions)
            assertEquals(TurnOutcome.COMPLETED,runtime.resume(turnId))
            assertEquals(1,calls);assertEquals(2,requests)
            assertEquals(LocalActionStatus.EXECUTED,store.pendingActions().single().status)
            assertEquals("Finished.",store.messages(channel.id).last().text)
            assertTrue(store.checkpoints().isEmpty())
            runtime.resume(turnId);assertEquals(1,calls)
        }finally{model.close();store.close()}
    }

    @Test fun `denial and a revoked grant never execute the remote action`()=runBlocking<Unit> {
        for(revoke in listOf(false,true)) {
            context.deleteDatabase("openbot.db")
            LocalStore(context).use{store->
                val channel=connect(store);val model=LocalModel(context);var requests=0;var calls=0
                try {
                    val runtime=AgentRuntime(context,store,model,configuration,ProviderTransport{_,_,request->
                        if(requests++==0)firstReply() else {
                            val result=request["input"]!!.jsonArray.last().jsonObject["output"]!!.jsonPrimitive.content
                            if(revoke)assertContains(result,"no longer granted") else assertEquals(DECLINED_BY_PERSON,result)
                            finalReply()
                        }
                    },{_,_->Session{calls++}})
                    runtime.turn(channel.id,"Save a note")
                    val action=store.pendingActions().single()
                    store.decideAction(action.id,if(revoke)LocalActionStatus.APPROVED else LocalActionStatus.DENIED)
                    if(revoke)store.saveConnection(store.connections().single().copy(grants=emptyMap()))
                    runtime.resume(action.turnId)
                    assertEquals(0,calls);assertEquals(2,requests)
                    assertEquals(if(revoke)LocalActionStatus.CANCELLED else LocalActionStatus.DENIED,store.pendingActions().single().status)
                }finally{model.close()}
            }
        }
    }
    @Test fun `process death during an external call preserves uncertainty and requires a result check`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val channel=connect(store);val model=LocalModel(context);var requests=0;var calls=0
            try {
                val runtime=AgentRuntime(context,store,model,configuration,ProviderTransport{_,_,_->requests++;firstReply()},{_,_->Session{calls++}})
                runtime.turn(channel.id,"Save a note")
                val action=store.pendingActions().single()
                store.decideAction(action.id,LocalActionStatus.APPROVED)
                store.claimContinuation(action.turnId)
                assertTrue(store.beginApprovedAction(action.id))
                assertFalse(store.beginApprovedAction(action.id))
                store.recoverContinuations()
                assertEquals(LocalActionStatus.UNCERTAIN,store.pendingActions().single().status)
                assertEquals(OUTCOME_UNKNOWN,store.toolEvents(channel.id).single().result)
                assertEquals(MessageState.INTERRUPTED,store.messages(channel.id).last().state)
                runtime.resume(action.turnId)
                assertEquals(0,calls);assertEquals(1,requests)
                store.resolveUnknownAction(action.id,false)
                assertEquals(LocalActionStatus.CANCELLED,store.pendingActions().single().status)
                assertContains(store.toolEvents(channel.id).single().result,"did not happen")
                assertFails{store.resolveUnknownAction(action.id,true)}
            }finally{model.close()}
        }
    }

    @Test fun `a changed model endpoint cannot receive a different endpoint credential on resume`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val channel=connect(store);val model=LocalModel(context);var requests=0;var sessions=0
            var settings=ProviderSettings()
            val dynamic=object:ModelConfiguration {
                override fun settings()=settings
                override fun hasKey()=true
                override fun apiKey()=if(settings.baseUrl=="https://api.openai.com/v1")"first-fixture-key" else "second-fixture-key"
            }
            try {
                val runtime=AgentRuntime(context,store,model,dynamic,ProviderTransport{config,key,_->
                    requests++;assertEquals("https://api.openai.com/v1",config.baseUrl);assertEquals("first-fixture-key",key);firstReply()
                },{_,_->sessions++;Session{error("No action should execute")}})
                runtime.turn(channel.id,"Save a note")
                val approval=store.pendingActions().single();store.decideAction(approval.id,LocalActionStatus.APPROVED)
                settings=settings.copy(baseUrl="https://other.example/v1")
                assertFails{runtime.resume(approval.turnId)}
                assertEquals(1,requests);assertEquals(0,sessions)
                assertEquals(LocalActionStatus.CANCELLED,store.pendingActions().single().status)
            }finally{model.close()}
        }
    }

    @Test fun `steps after a waiting action are not run when the person answers`()=runBlocking<Unit> {
        for(decision in listOf(LocalActionStatus.DENIED,LocalActionStatus.APPROVED)) {
            context.deleteDatabase("openbot.db")
            LocalStore(context).use{store->
                val channel=connect(store);val model=LocalModel(context);var requests=0;var calls=0
                try {
                    val initial=firstReply()
                    val first=initial["output"]!!.jsonArray.single().jsonObject
                    val later=buildJsonObject {
                        put("type","function_call");put("id","fc2");put("call_id","c2")
                        put("name",first["name"]!!);put("arguments",first["arguments"]!!)
                    }
                    // Even a local step must wait for replanning, rather than execute from the old batch.
                    val local=buildJsonObject {
                        put("type","function_call");put("id","fc3");put("call_id","c3")
                        put("name","unavailable_local_tool");put("arguments","{}")
                    }
                    val batched=buildJsonObject {put("status","completed");put("output",JsonArray(listOf(first,later,local)))}
                    val runtime=AgentRuntime(context,store,model,configuration,ProviderTransport{_,_,request->
                        if(requests++==0)batched else {
                            val results=request["input"]!!.jsonArray.map{it.jsonObject}.filter{it["type"]?.jsonPrimitive?.content=="function_call_output"}
                            assertEquals(3,results.size)
                            assertEquals(NOT_RUN_WHILE_WAITING,results[1]["output"]!!.jsonPrimitive.content)
                            assertEquals(NOT_RUN_WHILE_WAITING,results[2]["output"]!!.jsonPrimitive.content)
                            finalReply()
                        }
                    },{_,_->Session{calls++}})
                    assertEquals(TurnOutcome.WAITING,runtime.turn(channel.id,"Save a note"))
                    val action=store.pendingActions().single();store.decideAction(action.id,decision)
                    assertEquals(TurnOutcome.COMPLETED,runtime.resume(action.turnId))
                    assertEquals(if(decision==LocalActionStatus.APPROVED)1 else 0,calls)
                    val skipped=store.toolEvents(channel.id).filter{it.callId in listOf("c2","c3")}
                    assertEquals(2,skipped.size);assertTrue(skipped.all{it.result==NOT_RUN_WHILE_WAITING})
                    assertEquals(1,store.pendingActions().size)
                }finally{model.close()}
            }
        }
    }

}

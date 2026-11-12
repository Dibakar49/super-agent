package ai.openbot.android.inference

import ai.openbot.android.data.*
import ai.openbot.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class QuestionContinuationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val configuration=object:ModelConfiguration {
        override fun settings()=ProviderSettings()
        override fun hasKey()=true
        override fun apiKey()="fixture-question-key"
    }
    private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
    private val finalReply get()=obj("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Plans for Kolkata."}]}]}""")
    @BeforeTest fun reset(){context.deleteDatabase("openbot.db")}
    @AfterTest fun cleanup(){context.deleteDatabase("openbot.db")}

    @Test fun `a question survives reopening and an answer continues exactly once without running later old steps`()=runBlocking<Unit> {
        var store=LocalStore(context);var model=LocalModel(context);var requests=0
        try {
            val channel=store.newConversation(store.bots().single().id)
            val provider=ProviderTransport{_,_,request->
                if(requests++==0)obj("""{"status":"completed","output":[{"type":"function_call","id":"fc1","call_id":"q1","name":"ask_person","arguments":"{\"question\":\"Which city?\",\"why\":\"I need your location.\",\"parts\":[{\"header\":\"City\",\"question\":\"Where are you?\",\"options\":[]}] }"},{"type":"function_call","id":"fc2","call_id":"old-step","name":"unavailable_local_tool","arguments":"{}"}]}""")
                else {
                    val results=request["input"]!!.jsonArray.map{it.jsonObject}.filter{it["type"]?.jsonPrimitive?.content=="function_call_output"}
                    assertEquals(2,results.size)
                    assertEquals("In response to your question: Which city?\n\nCity: Kolkata",results[0]["output"]!!.jsonPrimitive.content)
                    assertEquals(NOT_RUN_WHILE_WAITING,results[1]["output"]!!.jsonPrimitive.content)
                    finalReply
                }
            }
            assertEquals(TurnOutcome.WAITING,AgentRuntime(context,store,model,configuration,provider).turn(channel.id,"Plan my weekend"))
            val question=QuestionStore(store).waiting().single()
            assertEquals(emptyList(),question.parts.single().options);assertEquals(1,requests)
            assertTrue(store.toolEvents(channel.id).isEmpty())
            store.close();model.close();store=LocalStore(context);model=LocalModel(context)
            assertEquals(question,QuestionStore(store).waiting().single())
            assertFails{QuestionStore(store).answer(question.id,"   ")}
            val checkpoint=QuestionStore(store).answer(question.id,"  City: Kolkata  ")
            assertFails{QuestionStore(store).answer(question.id,"Delhi")}
            assertTrue(QuestionStore(store).waiting().isEmpty())
            val response=store.messages(channel.id).last()
            assertEquals("person-response:${question.id}",response.id);assertEquals("City: Kolkata",response.displayText)
            val runtime=AgentRuntime(context,store,model,configuration,provider)
            assertEquals(TurnOutcome.COMPLETED,runtime.resume(checkpoint.turnId))
            assertEquals("Plans for Kolkata.",store.messages(channel.id).first{it.id==checkpoint.turnId}.text)
            runtime.resume(checkpoint.turnId);assertEquals(2,requests)
            assertEquals(2,store.toolEvents(channel.id).size)
            store.deleteConversation(channel.id);assertTrue(QuestionStore(store).list().isEmpty())
        }finally{model.close();store.close()}
    }

    @Test fun `a stopped question cannot be answered or resume its turn`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val model=LocalModel(context)
            try {
                val channel=store.newConversation(store.bots().single().id)
                val runtime=AgentRuntime(context,store,model,configuration,ProviderTransport{_,_,_->obj("""{"status":"completed","output":[{"type":"function_call","id":"fc1","call_id":"q1","name":"ask_person","arguments":"{\"question\":\"Which city?\"}"}]}""")})
                runtime.turn(channel.id,"Plan a trip")
                val question=QuestionStore(store).waiting().single()
                store.interruptContinuation(store.checkpoints().single(),"Stopped by you.",true)
                assertTrue(QuestionStore(store).waiting().isEmpty())
                assertFails{QuestionStore(store).answer(question.id,"Kolkata")}
                assertTrue(store.toolEvents(channel.id).isEmpty())
            }finally{model.close()}
        }
    }
}

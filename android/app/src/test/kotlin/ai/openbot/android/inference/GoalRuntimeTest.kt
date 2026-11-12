package ai.openbot.android.inference

import ai.openbot.android.data.*
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
class GoalRuntimeTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val configuration=object:ModelConfiguration {
        override fun settings()=ProviderSettings()
        override fun hasKey()=true
        override fun apiKey()="fixture-key"
    }
    private fun obj(value:String)=Json.parseToJsonElement(value).jsonObject
    @BeforeTest fun reset(){context.deleteDatabase("openbot.db")}
    @AfterTest fun cleanup(){context.deleteDatabase("openbot.db")}
    @Test fun `a goal run reports progress and a repeating goal remains active`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val goals=GoalStore(store);val channel=store.newConversation(store.bots().single().id);val now=Instant.now().toString()
            val goal=goals.save(LocalGoal("goal",channel.botId,channel.id,"Daily brief","Write a brief","Brief has sources",recurring=true,createdAt=now,updatedAt=now))
            val run=assertNotNull(goals.claim(goals.enqueue(goal.id).id));val model=LocalModel(context);var requests=0
            try {
                val provider=ProviderTransport{_,_,request->
                    if(requests++==0) {
                        assertTrue(request["tools"]!!.jsonArray.any{it.jsonObject["name"]!!.jsonPrimitive.content=="report_goal_progress"})
                        obj("""{"status":"completed","output":[{"type":"function_call","call_id":"progress","name":"report_goal_progress","arguments":"{\"id\":\"goal\",\"summary\":\"Today's brief is written with sources.\",\"complete\":true}"}]}""")
                    } else {
                        assertContains(request["input"]!!.jsonArray.last().jsonObject["output"]!!.jsonPrimitive.content,"stays active")
                        obj("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Today's brief is ready."}]}]}""")
                    }
                }
                val runtime=AgentRuntime(context,store,model,configuration,provider,enqueueGoals={})
                assertEquals(TurnOutcome.COMPLETED,runtime.turn(channel.id,frameGoal(goal,run.id),goalRunId=run.id))
                assertEquals(GoalStatus.ACTIVE,goals.get(goal.id).status)
                assertEquals("Today's brief is written with sources.",goals.get(goal.id).progress)
                assertEquals(store.messages(channel.id).last().id,goals.run(run.id)!!.turnId)
                assertEquals("Today's brief is ready.",store.messages(channel.id).last().text)
                goals.finish(run.id,GoalRunStatus.SUCCEEDED,store.messages(channel.id).last().text)
            }finally{model.close()}
        }
    }
    @Test fun `run goal commits its outbox and tool result before background scheduling`()=runBlocking<Unit> {
        LocalStore(context).use{store->
            val goals=GoalStore(store);val channel=store.newConversation(store.bots().single().id);val now=Instant.now().toString()
            goals.save(LocalGoal("goal",channel.botId,channel.id,"Brief","Write a brief","Has sources",createdAt=now,updatedAt=now))
            val model=LocalModel(context);var scheduled=false
            try {
                val runtime=AgentRuntime(context,store,model,configuration,enqueueGoals={
                    LocalStore(context).use{reader->
                        assertEquals(1,GoalStore(reader).queuedRuns().size)
                        assertEquals(1,reader.toolEvents(channel.id).size)
                    };scheduled=true
                })
                val call=ToolCall("run","run_goal",obj("""{"id":"goal"}"""))
                val first=runtime.executeRecorded(call,channel.botId,channel.id,"turn")
                assertTrue(scheduled)
                assertEquals(first,runtime.executeRecorded(call,channel.botId,channel.id,"turn"))
                assertEquals(1,goals.queuedRuns().size)
            }finally{model.close()}
        }
    }
}

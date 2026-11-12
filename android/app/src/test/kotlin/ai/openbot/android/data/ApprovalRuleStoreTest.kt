package ai.openbot.android.data

import ai.openbot.core.*
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ApprovalRuleStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store:LocalStore
    @BeforeTest fun setup(){context.deleteDatabase("openbot.db");store=LocalStore(context)}
    @AfterTest fun cleanup(){store.close();context.deleteDatabase("openbot.db")}
    @Test fun `rule edits retain identity and preferences survive reopening`() {
        val rules=ApprovalRuleStore(store)
        rules.savePreferences(ApprovalPreferences(true,true))
        val rule=ApprovalRule("rule","*","notes/*","write","notes",RuleBehaviour.ASK)
        rules.save(rule);rules.save(rule.copy(behaviour=RuleBehaviour.PRE_APPROVED))
        assertFails{rules.save(rule.copy(scope=" "))}
        assertEquals(1,rules.rules().size)
        store.close();store=LocalStore(context)
        val reopened=ApprovalRuleStore(store)
        assertEquals(ApprovalPreferences(true,true),reopened.preferences())
        assertEquals("rule",reopened.rules().single().id)
        assertEquals(RuleBehaviour.PRE_APPROVED,reopened.rules().single().behaviour)
        reopened.delete("rule");assertTrue(reopened.rules().isEmpty())
    }
    @Test fun `withdrawing an old default approval records no effect and queues its continuation`() {
        val rules=ApprovalRuleStore(store);rules.savePreferences(ApprovalPreferences(true,false))
        val channel=store.newConversation(store.bots().single().id);val now=Instant.now().toString()
        val schema=Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject
        val tool=McpTool("write_note",inputSchema=schema)
        val call=ToolCall("call","mcp_note",buildJsonObject{})
        store.beginTurn(LocalMessage("u",channel.id,"user","Write a note",now),LocalMessage("a",channel.id,"assistant","Working",now,MessageState.GENERATING))
        val action=PendingLocalAction("approval","a",channel.id,channel.botId,"notes","https://example.com/mcp",null,tool,call,now,policy=LocalPolicyOutcome(PolicyBehaviour.ASK,"default","Ask first"))
        store.waitForApproval(AgentCheckpoint("a",channel.id,channel.botId,ProviderSettings(),emptyList(),JsonArray(emptyList()),AgentRound("",listOf(call),emptyList()),emptyList(),action.id,now),action)
        rules.savePreferences(ApprovalPreferences(false,false))
        assertEquals(listOf("a"),rules.withdraw(WITHDRAWN_APPROVALS_OFF){it.policy?.source=="default"})
        assertEquals(LocalActionStatus.CANCELLED,store.pendingActions().single().status)
        assertEquals(ContinuationPhase.QUEUED,store.checkpoints().single().phase)
        assertEquals(WITHDRAWN_APPROVALS_OFF,store.toolEvents(channel.id).single().result)
        assertTrue(rules.withdraw(WITHDRAWN_APPROVALS_OFF){true}.isEmpty())
    }
}

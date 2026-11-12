package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.test.*

class AgentProtocolTest {
    private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
    private val tool=AgentTool("list_routines","List routines",obj("""{"type":"object","properties":{}}"""))
    @Test fun `Responses preserves function calls and matching result identities`() {
        val protocol=AgentProtocol(ProviderSettings(),"Hello",listOf(tool))
        assertEquals("function",protocol.request()["tools"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        val round=protocol.read(obj("""{"status":"completed","output":[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"list_routines","arguments":"{}"}]}"""))
        assertEquals("list_routines",round.calls.single().name)
        protocol.continueWith(round,listOf("[]"))
        val history=protocol.request()["input"]!!.jsonArray
        assertEquals(3,history.size)
        assertEquals("call_1",history.last().jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("[]",history.last().jsonObject["output"]!!.jsonPrimitive.content)
        val answer=protocol.read(obj("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"No routines."}]}]}"""))
        assertEquals("No routines.",answer.text);assertTrue(answer.calls.isEmpty())
    }
    @Test fun `Chat Completions preserves assistant and tool roles`() {
        val protocol=AgentProtocol(ProviderSettings(apiStyle=ApiStyle.CHAT_COMPLETIONS),"Hello",listOf(tool))
        val round=protocol.read(obj("""{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_2","type":"function","function":{"name":"list_routines","arguments":"{}"}}]}}]}"""))
        protocol.continueWith(round,listOf("[]"))
        val history=protocol.request()["messages"]!!.jsonArray
        assertEquals(listOf("user","assistant","tool"),history.map{it.jsonObject["role"]!!.jsonPrimitive.content})
        assertEquals("call_2",history.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertFalse(protocol.request().containsKey("store"))
    }
    @Test fun `incomplete output malformed arguments and duplicate call ids cannot execute`() {
        val protocol=AgentProtocol(ProviderSettings(),"Hello",listOf(tool))
        assertFails{protocol.read(obj("""{"status":"incomplete","output":[{"type":"function_call","call_id":"c","name":"list_routines","arguments":"{}"}]}"""))}
        assertFails{protocol.read(obj("""{"status":"completed","output":[{"type":"function_call","call_id":"c","name":"list_routines","arguments":"[]"}]}"""))}
        assertFails{protocol.read(obj("""{"status":"completed","output":[{"type":"function_call","call_id":"c","name":"list_routines","arguments":"{}"},{"type":"function_call","call_id":"c","name":"list_routines","arguments":"{}"}]}"""))}
    }
    @Test fun `tool arguments reject type confusion and unknown strict fields before dispatch`() {
        val memory=memoryTools.first{it.name=="remember_personal_fact"}
        validateToolArguments(memory,obj("""{"content":"A fact","enabled":false}"""))
        assertFails{validateToolArguments(memory,obj("""{"content":"A fact","enabled":"false"}"""))}
        assertFails{validateToolArguments(memory,obj("""{"content":123}"""))}
        assertFails{validateToolArguments(memory,obj("""{"content":"A fact","owner":"other"}"""))}
        assertFails{validateToolArguments(memory,obj("""{}"""))}
    }

}

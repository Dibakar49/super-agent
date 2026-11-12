package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderStreamTest {
    @Test fun `SSE framing accepts comments multiline data and CR stripped by reader`() {
        val events = ServerSentEvents()
        assertNull(events.line(": heartbeat"))
        assertNull(events.line("event: response.output_text.delta"))
        assertNull(events.line("data: {\"text\":"))
        assertNull(events.line("data: \"नमस्ते\"}"))
        assertEquals("{\"text\":\n\"नमस्ते\"}", events.line(""))
        assertNull(events.line(""))
    }

    @Test fun `Responses displays text before terminal and retains complete reasoning and function items`() {
        val stream = ProviderStream(ApiStyle.RESPONSES)
        assertFalse(stream.accept("""{"type":"response.output_text.delta","delta":"नमस्ते 🌍"}"""))
        assertEquals("नमस्ते 🌍", stream.visibleText)
        assertFailsWith<IllegalStateException> { stream.finish() }
        assertTrue(stream.accept("""{"type":"response.completed","response":{"status":"completed","output":[{"type":"reasoning","encrypted_content":"opaque"},{"type":"function_call","call_id":"c","name":"list_routines","arguments":"{}"}]}}"""))
        val round = AgentProtocol(ProviderSettings(), "Hello", emptyList()).read(stream.finish())
        assertEquals("list_routines", round.calls.single().name)
        assertEquals("opaque", round.continuation.first()["encrypted_content"]!!.jsonPrimitive.content)
    }

    @Test fun `Chat Completions assembles fragmented parallel calls only after finish and DONE`() {
        val stream = ProviderStream(ApiStyle.CHAT_COMPLETIONS)
        stream.accept("""{"choices":[{"index":0,"delta":{"content":"Looking…","tool_calls":[{"index":0,"id":"a","function":{"name":"recall_","arguments":"{\"search\":"}},{"index":1,"id":"b","function":{"name":"list_routines","arguments":"{"}}]},"finish_reason":null}]}""")
        assertEquals("Looking…", stream.visibleText)
        stream.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"personal_memory","arguments":"\"trip\"}"}},{"index":1,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}""")
        assertFailsWith<IllegalStateException> { stream.finish() }
        assertTrue(stream.accept("[DONE]"))
        val round = AgentProtocol(ProviderSettings(apiStyle = ApiStyle.CHAT_COMPLETIONS), "Hello", emptyList()).read(stream.finish())
        assertEquals(listOf("a", "b"), round.calls.map { it.id })
        assertEquals("recall_personal_memory", round.calls.first().name)
        assertEquals("trip", round.calls.first().arguments["search"]!!.jsonPrimitive.content)
    }

    @Test fun `truncated malformed failed and filtered streams cannot supply executable calls`() {
        assertFailsWith<IllegalStateException> { ProviderStream(ApiStyle.CHAT_COMPLETIONS).accept("[DONE]") }
        assertFailsWith<IllegalStateException> { ProviderStream(ApiStyle.RESPONSES).accept("not json") }
        for (event in listOf("response.failed", "response.incomplete", "error")) {
            assertFailsWith<IllegalStateException> { ProviderStream(ApiStyle.RESPONSES).accept("""{"type":"$event"}""") }
        }
        for (reason in listOf("length", "content_filter")) {
            assertFailsWith<IllegalStateException> { ProviderStream(ApiStyle.CHAT_COMPLETIONS).accept("""{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}""") }
        }
        assertFailsWith<IllegalStateException> { ProviderStream(ApiStyle.RESPONSES).accept("""{"type":"response.completed","response":{"status":"incomplete","output":[]}}""") }
    }

    @Test fun `malformed final tool arguments reject the entire model round`() {
        val stream = ProviderStream(ApiStyle.CHAT_COMPLETIONS)
        stream.accept("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"list_routines","arguments":"{}"}},{"index":1,"id":"b","function":{"name":"list_routines","arguments":"{"}}]},"finish_reason":"tool_calls"}]}""")
        stream.accept("[DONE]")
        assertFails { AgentProtocol(ProviderSettings(apiStyle = ApiStyle.CHAT_COMPLETIONS), "Hello", emptyList()).read(stream.finish()) }
    }
}

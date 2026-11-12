package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.test.*

class ModelProviderTest {
    @Test fun `provider endpoint preserves versioned path and rejects insecure or credential URLs`() {
        val settings = ProviderSettings(baseUrl = "https://example.com/proxy/v1/")
        assertEquals("https://example.com/proxy/v1/responses", providerEndpoint(settings).toString())
        assertEquals("https://example.com/proxy/v1/chat/completions", providerEndpoint(settings.copy(apiStyle = ApiStyle.CHAT_COMPLETIONS)).toString())
        listOf("http://example.com/v1", "https://user:key@example.com/v1", "https://example.com/v1?token=secret", "https://example.com/v1#part", "file:///tmp/model").forEach {
            assertFailsWith<IllegalArgumentException> { providerEndpoint(settings.copy(baseUrl = it)) }
        }
    }

    @Test fun `Responses requests do not ask the provider to store conversation state`() {
        val request = providerRequest(ProviderSettings(), "Hello")
        assertEquals(JsonPrimitive(false), request["store"])
        assertEquals(JsonPrimitive("Hello"), request["input"])
        assertFalse(request.containsKey("api_key"))
    }

    @Test fun `text extraction handles Responses and Chat completions and rejects incomplete output`() {
        val responses = Json.parseToJsonElement("""{"status":"completed","output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"Hello"},{"type":"output_text","text":"World"}]}]}""").jsonObject
        assertEquals("Hello\nWorld", providerAnswer(ApiStyle.RESPONSES, responses))
        val chat = Json.parseToJsonElement("""{"choices":[{"message":{"role":"assistant","content":"Hello"}}]}""").jsonObject
        assertEquals("Hello", providerAnswer(ApiStyle.CHAT_COMPLETIONS, chat))
        assertFailsWith<IllegalStateException> { providerAnswer(ApiStyle.RESPONSES, JsonObject(responses + ("status" to JsonPrimitive("incomplete")))) }
        assertFailsWith<IllegalStateException> { providerAnswer(ApiStyle.CHAT_COMPLETIONS, buildJsonObject {}) }
    }
}

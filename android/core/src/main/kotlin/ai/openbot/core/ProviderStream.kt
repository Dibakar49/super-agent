package ai.openbot.core

import kotlinx.serialization.json.*

/** SSE event framing is independent of socket chunks and ignores comments/heartbeats. */
class ServerSentEvents {
    private val data = StringBuilder()
    private var hasData = false

    fun line(line: String): String? {
        if (line.isEmpty()) {
            val event = data.toString().takeIf { hasData }
            data.clear()
            hasData = false
            return event
        }
        if (line.startsWith(":")) return null
        val field = line.substringBefore(':')
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(line.substringAfter(':', "").removePrefix(" "))
            hasData = true
            check(data.length <= 2 * 1024 * 1024) { "The streamed event was too large." }
        }
        return null
    }
}

/** Assemble complete tool calls before AgentProtocol may inspect or execute any of them. */
class ProviderStream(private val style: ApiStyle) {
    private data class FunctionParts(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val arguments: StringBuilder = StringBuilder())
    private val tools = sortedMapOf<Int, FunctionParts>()
    private val text = StringBuilder()
    private var reason: String? = null
    private var response: JsonObject? = null
    private var done = false
    val visibleText: String get() = text.toString()

    /** True only when the wire protocol has supplied its successful terminal event. */
    fun accept(data: String): Boolean {
        check(!done) { "The model sent data after its final response." }
        if (data == "[DONE]") {
            check(style == ApiStyle.CHAT_COMPLETIONS && reason != null) { "The model stream ended before its answer was complete; no tools were executed." }
            check(tools.isEmpty() || reason == "tool_calls") { "The model stream ended with incomplete tool calls." }
            response = buildJsonObject {
                putJsonArray("choices") { addJsonObject {
                    put("finish_reason", reason)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", text.toString())
                        if (tools.isNotEmpty()) putJsonArray("tool_calls") {
                            tools.values.forEach { parts -> addJsonObject {
                                put("id", parts.id.toString()); put("type", "function")
                                putJsonObject("function") { put("name", parts.name.toString()); put("arguments", parts.arguments.toString()) }
                            } }
                        }
                    }
                } }
            }
            done = true
            return true
        }
        val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrElse {
            error("The model sent a malformed streamed event; no tools were executed.")
        }
        check(!event.containsKey("error")) { "The model provider reported a streaming error." }
        if (style == ApiStyle.RESPONSES) {
            when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "error", "response.failed", "response.incomplete" -> error("The model stream failed or was incomplete; no tools were executed.")
                "response.output_text.delta" -> append(event["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "response.completed" -> {
                    val final = event["response"] as? JsonObject ?: error("The model sent no completed response.")
                    check(final["status"]?.jsonPrimitive?.contentOrNull == "completed") { "The model response was incomplete; no tools were executed." }
                    response = final
                    done = true
                }
            }
        } else {
            val choices = event["choices"] as? JsonArray ?: error("The model sent an invalid streaming choice.")
            if (choices.isEmpty()) return false // Optional usage-only chunk.
            check(choices.size == 1) { "The model sent multiple streaming choices." }
            val choice = choices.single().jsonObject
            check(choice["index"]?.jsonPrimitive?.intOrNull == 0) { "The model sent an unexpected streaming choice." }
            val delta = choice["delta"] as? JsonObject ?: JsonObject(emptyMap())
            check(reason == null) { "The model sent content after finishing its answer." }
            check(delta["refusal"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) { "The model provider declined this answer." }
            append(delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
            (delta["tool_calls"] as? JsonArray).orEmpty().forEach { item ->
                val part = item.jsonObject
                val index = part["index"]?.jsonPrimitive?.intOrNull ?: error("The model sent a tool call without an index.")
                check(index in 0..31) { "The model sent too many tool calls." }
                val target = tools.getOrPut(index) { FunctionParts() }
                target.id.append(part["id"]?.jsonPrimitive?.contentOrNull.orEmpty())
                val function = part["function"] as? JsonObject
                target.name.append(function?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty())
                target.arguments.append(function?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty())
                check(target.arguments.length <= 256_000 && target.name.length <= 256 && target.id.length <= 256) { "The streamed tool call was too large." }
            }
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
                check(it in setOf("stop", "tool_calls")) { "The model response was incomplete; no tools were executed." }
                reason = it
            }
        }
        return done
    }

    private fun append(value: String) {
        check(text.length + value.length <= 128_000) { "The streamed answer was too large." }
        text.append(value)
    }

    fun finish(): JsonObject {
        check(done) { "The model stream disconnected before its answer was complete; no tools were executed." }
        return requireNotNull(response)
    }
}

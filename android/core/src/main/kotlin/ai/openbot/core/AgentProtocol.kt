package ai.openbot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class AgentTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
)

@Serializable data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

@Serializable data class AgentRound(
    val text: String,
    val calls: List<ToolCall>,
    val continuation: List<JsonObject>,
)

/** Provider wire history stays structured: a tool result is never disguised as a person's message. */
class AgentProtocol(
    private val settings: ProviderSettings,
    prompt: String,
    private val tools: List<AgentTool>,
    restoredHistory: JsonArray? = null,
    initialContent: JsonArray? = null,
) {
    private val history =
        restoredHistory?.map { it.jsonObject }?.toMutableList() ?: mutableListOf(
            buildJsonObject {
                put("role", "user")
                put("content", initialContent ?: JsonPrimitive(prompt))
            },
        )

    fun snapshot(): JsonArray = JsonArray(history.toList())

    fun request(): JsonObject =
        buildJsonObject {
            put("model", settings.model)
            put(if (settings.apiStyle == ApiStyle.RESPONSES)"input" else "messages", JsonArray(history.toList()))
            if (settings.apiStyle == ApiStyle.RESPONSES) {
                put("store", false)
                put("max_output_tokens", 4096)
                put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
            }
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JsonArray(
                        tools.map { tool ->
                            val function =
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.schema)
                                }
                            if (settings.apiStyle == ApiStyle.CHAT_COMPLETIONS) {
                                buildJsonObject {
                                    put("type", "function")
                                    put("function", function)
                                }
                            } else {
                                buildJsonObject {
                                    put("type", "function")
                                    function.forEach { (key, value) -> put(key, value) }
                                }
                            }
                        },
                    ),
                )
            }
        }

    fun read(response: JsonObject): AgentRound {
        val calls = mutableListOf<ToolCall>()
        val continuation: List<JsonObject>
        val text: String
        if (settings.apiStyle == ApiStyle.RESPONSES) {
            val status = response["status"]?.jsonPrimitive?.contentOrNull
            check(status == null || status == "completed") { "The model response was incomplete; no tools were executed." }
            continuation = (response["output"] as? JsonArray).orEmpty().map { it.jsonObject }
            text =
                continuation
                    .flatMap { (it["content"] as? JsonArray).orEmpty() }
                    .mapNotNull {
                        it.jsonObject
                            .takeIf { item -> item["type"]?.jsonPrimitive?.content == "output_text" }
                            ?.get("text")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }.joinToString("\n")
            continuation.filter { it["type"]?.jsonPrimitive?.content == "function_call" }.forEach {
                calls.add(call(it, "call_id", it))
            }
        } else {
            val choice = (response["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: error("The model returned no answer.")
            check(choice["finish_reason"]?.jsonPrimitive?.contentOrNull !in listOf("length", "content_filter")) { "The model response was incomplete; no tools were executed." }
            val message = choice["message"]?.jsonObject ?: error("The model returned no answer.")
            continuation = listOf(message)
            text = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            (message["tool_calls"] as? JsonArray).orEmpty().forEach { item ->
                val obj = item.jsonObject
                calls.add(call(obj, "id", obj["function"]!!.jsonObject))
            }
        }
        check(calls.size <= 32 && calls.map { it.id }.distinct().size == calls.size) { "The model returned an invalid set of tool calls." }
        check(text.isNotBlank() || calls.isNotEmpty()) { "The model returned no text answer." }
        return AgentRound(text, calls, continuation)
    }

    private fun call(
        obj: JsonObject,
        idKey: String,
        function: JsonObject,
    ): ToolCall {
        val id = obj[idKey]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("The model returned a tool call without an identity.")
        val name = function["name"]?.jsonPrimitive?.contentOrNull ?: error("The model returned an unnamed tool call.")
        val args = function["arguments"]?.jsonPrimitive?.contentOrNull ?: error("The model returned invalid tool arguments.")
        return ToolCall(id, name, Json.parseToJsonElement(args).jsonObject)
    }

    fun continueWith(
        round: AgentRound,
        results: List<String>,
    ) {
        require(results.size == round.calls.size)
        history.addAll(round.continuation)
        round.calls.zip(results).forEach { (call, result) ->
            history.add(
                buildJsonObject {
                    if (settings.apiStyle == ApiStyle.RESPONSES) {
                        put("type", "function_call_output")
                        put("call_id", call.id)
                        put("output", result)
                    } else {
                        put("role", "tool")
                        put("tool_call_id", call.id)
                        put("content", result)
                    }
                },
            )
        }
        check(history.sumOf { it.toString().length } <= 512_000) { "This tool run exceeded the model context budget. Start a new conversation." }
    }
}

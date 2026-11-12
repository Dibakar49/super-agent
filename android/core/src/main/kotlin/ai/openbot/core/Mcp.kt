package ai.openbot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest

const val MCP_VERSION = "2025-11-25"
val MCP_HTTP_VERSIONS = setOf(MCP_VERSION, "2025-06-18", "2025-03-26")

@Serializable data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject,
    val annotations: JsonObject = buildJsonObject {},
)

@Serializable data class LocalConnection(
    val id: String,
    val name: String,
    val url: String,
    val tools: List<McpTool> = emptyList(),
    val serverName: String? = null,
    val credentialRef: String? = null,
    val grants: Map<String, List<String>> = emptyMap(),
    val updatedAt: String,
)

fun mcpEndpoint(value: String): URI {
    val uri = runCatching { URI(value.trim()) }.getOrElse { error("Enter a valid MCP server URL.") }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null) { "Use an HTTPS MCP URL without credentials, query parameters, or a fragment." }
    return uri
}

fun mcpRequest(
    id: String,
    method: String,
    params: JsonObject = buildJsonObject {},
): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

fun mcpInitialize(id: String) =
    mcpRequest(
        id,
        "initialize",
        buildJsonObject {
            put("protocolVersion", MCP_VERSION)
            put("capabilities", buildJsonObject {})
            putJsonObject("clientInfo") {
                put("name", "OpenBot Android")
                put("version", "0.1.0")
            }
        },
    )

/** Streamable HTTP can answer JSON or an SSE stream containing progress and the final JSON-RPC reply. */
fun readMcpReply(
    body: String,
    contentType: String,
    id: String,
): JsonObject {
    val messages =
        if (contentType.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)) {
            val events = mutableListOf<String>()
            val data = mutableListOf<String>()

            fun dispatch() {
                if (data.isNotEmpty()) {
                    events.add(data.joinToString("\n"))
                    data.clear()
                }
            }
            body.lineSequence().forEach { line ->
                if (line.isEmpty()) {
                    dispatch()
                } else if (line.startsWith("data:")) {
                    data.add(line.substring(5).removePrefix(" "))
                }
            }
            dispatch()
            events.mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        } else {
            require(contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)) { "The MCP server returned an unsupported content type." }
            listOf(Json.parseToJsonElement(body).jsonObject)
        }
    val reply = messages.lastOrNull { it["id"] == JsonPrimitive(id) } ?: error("The MCP server did not return a matching response.")
    require(reply["jsonrpc"] == JsonPrimitive("2.0")) { "The server did not return a JSON-RPC 2.0 response." }
    if (reply.containsKey("error"))error("The MCP server refused the request. ${reply["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty().take(400)}")
    return reply["result"] as? JsonObject ?: error("The MCP server returned an invalid result.")
}

/** Return as soon as the matching SSE reply arrives; do not wait for a persistent stream to close. */
fun readMcpStream(
    input: java.io.InputStream,
    contentType: String,
    id: String,
    maximumBytes: Int = 4 * 1024 * 1024,
): JsonObject {
    val bounded =
        object : java.io.FilterInputStream(input) {
            private var count = 0

            override fun read(): Int {
                val next = super.read()
                if (next >= 0)check(++count <= maximumBytes) { "The MCP response exceeded its size limit." }
                return next
            }

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                val size = super.read(buffer, offset, minOf(length, (maximumBytes - count + 1).coerceAtLeast(1)))
                if (size > 0) {
                    count += size
                    check(count <= maximumBytes) { "The MCP response exceeded its size limit." }
                }
                return size
            }
        }
    if (!contentType.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true))return readMcpReply(bounded.readBytes().toString(Charsets.UTF_8), contentType, id)
    val reader = bounded.bufferedReader(Charsets.UTF_8)
    val data = mutableListOf<String>()

    fun reply(): JsonObject? {
        if (data.isEmpty())return null
        val body = data.joinToString("\n")
        data.clear()
        val message = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (message["id"] != JsonPrimitive(id))return null
        return readMcpReply(body, "application/json", id)
    }
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            reply()?.let { return it }
        } else if (line.startsWith("data:")) {
            data.add(line.substring(5).removePrefix(" "))
        }
    }
    return reply() ?: error("The MCP stream ended without a matching response.")
}

fun mcpModelToolName(
    connectionId: String,
    toolName: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$connectionId\u0000$toolName".toByteArray()).joinToString("") { "%02x".format(it) }
    return "mcp_${digest.take(40)}"
}

fun LocalConnection.modelTools(): List<AgentTool> = tools.map { tool -> AgentTool(mcpModelToolName(id, tool.name), "$name: ${tool.name}. ${tool.description}", tool.inputSchema) }

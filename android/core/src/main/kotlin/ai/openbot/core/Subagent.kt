package ai.openbot.core

import kotlinx.serialization.json.*
import java.security.MessageDigest

const val SUBAGENT_TOOL = "run_subagent"
const val SUBAGENTS_PER_ANSWER = 5

/** Decodes the original tool-result shape, including a JSON string encoded twice. */
fun readSubagentResult(text: String): JsonObject? {
    val parsed =
        runCatching {
            val first = Json.parseToJsonElement(text)
            if (first is JsonPrimitive && first.isString) Json.parseToJsonElement(first.content) else first
        }.getOrNull() as? JsonObject ?: return null

    fun JsonElement?.isText() = this is JsonPrimitive && isString
    if (!parsed["threadId"].isText() || (parsed["steps"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull == null) return null
    if (parsed["answer"].isText() || parsed["error"].isText()) return parsed
    val handedBack = parsed["handedBack"] as? JsonObject ?: return null
    return parsed.takeIf { handedBack["tool"].isText() && handedBack["reason"].isText() && parsed["next"].isText() }
}

/** SHA-256-derived UUIDv8, byte-compatible with shared/subagent.ts. */
fun subagentThreadId(
    parentThreadId: String,
    toolCallId: String,
): String {
    val bytes =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$SUBAGENT_TOOL\n$parentThreadId\n$toolCallId".toByteArray(Charsets.UTF_8))
            .copyOf(16)
    if (Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE).matches(parentThreadId)) {
        val hex = parentThreadId.replace("-", "")
        for (index in 0 until 6) bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20)).joinToString("-")
}

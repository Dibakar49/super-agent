package ai.openbot.android.inference

import ai.openbot.android.data.AttachmentStore
import ai.openbot.core.*
import kotlinx.serialization.json.*

internal data class TurnAttachments(
    val content: JsonArray?,
    val selected: List<LocalAttachment>,
)

/** The current send is included whole; older files share the remaining original 32 MB budget. */
internal fun prepareTurnAttachments(
    settings: ProviderSettings,
    channelId: String,
    prompt: String,
    history: List<LocalMessage>,
    includedMessages: List<String>,
    current: LocalMessage,
    currentFiles: List<LocalAttachment>,
    store: AttachmentStore,
): TurnAttachments {
    val byId = store.conversation(channelId).associateBy { it.id }
    val selected = mutableListOf<LocalAttachment>()
    selected.addAll(currentFiles)
    var remaining = MAX_INLINED_BYTES_PER_RUN - currentFiles.sumOf { it.size }
    require(remaining >= 0) { "This message's attachments exceed the 32MB limit. Send fewer files in each message." }
    val parts = mutableMapOf<String, List<JsonObject>>()

    fun text(value: String) =
        buildJsonObject {
            put("type", if (settings.apiStyle == ApiStyle.RESPONSES)"input_text" else "text")
            put("text", value)
        }

    fun part(attachment: LocalAttachment): JsonObject {
        val ref = attachmentReference(channelId, attachment.id)
        if (classifyAttachment(attachment.mimeType) == AttachmentKind.TEXT)return text(ref)
        return buildJsonObject {
            put("type", if (settings.apiStyle == ApiStyle.RESPONSES)"input_image" else "image_url")
            if (settings.apiStyle == ApiStyle.RESPONSES)put("image_url", ref) else put("image_url", buildJsonObject { put("url", ref) })
        }
    }
    for (message in history.filter { it.id != current.id && it.id in includedMessages }.asReversed()) {
        parts[message.id] =
            message.attachmentIds.map { id ->
                val attachment = byId[id]
                if (attachment == null || store.preview(id, null, channelId) == null) {
                    text("[attachment \"${attachment?.name ?: id}\" is no longer available]")
                } else if (attachment.size > remaining) {
                    remaining = 0
                    text("[attachment \"${attachment.name}\" from an earlier message was not included in this turn]")
                } else {
                    remaining -= attachment.size
                    selected.add(attachment)
                    part(attachment)
                }
            }
    }
    if (selected.isEmpty() && parts.values.all { it.isEmpty() })return TurnAttachments(null, emptyList())
    val content =
        buildJsonArray {
            add(text(prompt))
            for (message in history.filter { it.id != current.id && it.id in includedMessages }) {
                val values = parts[message.id].orEmpty()
                if (values.isNotEmpty()) {
                    add(text("Files from the earlier user message at ${message.createdAt}: ${message.text.take(300)}"))
                    values.forEach { add(it) }
                }
            }
            if (currentFiles.isNotEmpty()) {
                add(text("Files attached to the current user message:"))
                currentFiles.forEach { add(part(it)) }
            }
        }
    return TurnAttachments(content, selected)
}

/** Restore only references actually kept in the durable provider history, bound to its conversation. */
internal fun checkpointAttachments(
    history: JsonArray,
    channelId: String,
    store: AttachmentStore,
): List<LocalAttachment> {
    val available = store.conversation(channelId).associateBy { attachmentReference(channelId, it.id) }
    val found = mutableSetOf<String>()

    fun visit(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                attachmentPartReference(value)?.let { found.add(it) }
                value.values.forEach { visit(it) }
            }
            is JsonArray -> value.forEach { visit(it) }
            is JsonPrimitive -> Unit
        }
    }
    visit(history)
    return found.map { available[it] ?: error("An attachment from this waiting turn is no longer available.") }
}

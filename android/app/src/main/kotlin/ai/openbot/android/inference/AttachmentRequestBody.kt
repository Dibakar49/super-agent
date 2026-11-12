package ai.openbot.android.inference

import ai.openbot.android.data.*
import ai.openbot.core.*
import android.content.Context
import android.util.Base64
import android.util.Base64OutputStream
import kotlinx.serialization.json.*
import java.io.OutputStream

private const val ATTACHMENT_REFERENCE = "openbot-attachment:"

fun attachmentReference(
    channelId: String,
    id: String,
) = "$ATTACHMENT_REFERENCE$channelId:$id"

internal fun attachmentPartReference(part: JsonObject): String? {
    val type = part["type"]?.let { it as? JsonPrimitive }?.content
    val value =
        when (type) {
            "input_text", "text" -> part["text"] as? JsonPrimitive
            "input_image" -> part["image_url"] as? JsonPrimitive
            "image_url" -> (part["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive
            else -> null
        }?.takeIf { it.isString }?.content
    return value?.takeIf { it.startsWith(ATTACHMENT_REFERENCE) }
}

/** Only the attachment ids selected for this local turn can be expanded into model content. */
class AttachmentWireSource(
    context: Context,
    private val store: LocalStore,
) {
    private val attachments = AttachmentStore(context, store)
    private var channel: String? = null
    private var allowed: Map<String, LocalAttachment> = emptyMap()

    fun authorize(
        channelId: String,
        selected: List<LocalAttachment>,
    ) {
        channel = channelId
        allowed = selected.associateBy { attachmentReference(channelId, it.id) }
    }

    fun clear() {
        channel = null
        allowed = emptyMap()
    }

    fun metadata(reference: String): LocalAttachment {
        val expected = allowed[reference] ?: error("An attachment was not selected for this turn.")
        val current = attachments.get(expected.id) ?: error("'${expected.name}' is no longer available. Attach it again.")
        require(current.channelId == channel && current.messageId != null && current.mimeType == expected.mimeType && current.size == expected.size) { "An attachment no longer belongs to this turn." }
        val file = attachments.preview(current.id, null, channel) ?: error("'${current.name}' is no longer available. Attach it again.")
        require(file.length() == current.size) { "'${current.name}' changed after it was attached. Attach it again." }
        return current
    }

    fun open(attachment: LocalAttachment) = attachments.open(attachment.id, requireNotNull(channel))
}

/** Streams images as base64, rather than allocating a second full copy of the upload in memory. */
class AttachmentRequestBody(
    private val request: JsonObject,
    private val source: AttachmentWireSource? = null,
) {
    private val text = mutableMapOf<String, ByteArray>()
    private var inlined = 0L
    private var counted: Long? = null

    private class Counter : OutputStream() {
        var count = 0L

        override fun write(value: Int) {
            count++
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            count += length
        }

        fun add(size: Long) {
            count += size
        }
    }

    fun byteCount(): Long {
        counted?.let { return it }
        val output = Counter()
        inlined = 0
        render(request, output, true)
        return output.count.also { counted = it }
    }

    fun writeTo(output: OutputStream) {
        byteCount()
        inlined = 0
        render(request, output, false)
    }

    private fun write(
        output: OutputStream,
        value: String,
    ) {
        output.write(value.toByteArray(Charsets.UTF_8))
    }

    private fun quoted(
        output: OutputStream,
        value: String,
    ) {
        write(output, JsonPrimitive(value).toString())
    }

    private fun render(
        value: JsonElement,
        output: OutputStream,
        countOnly: Boolean,
    ) {
        when (value) {
            is JsonObject -> {
                val ref = attachmentPartReference(value)
                if (ref != null) {
                    renderAttachment(value, ref, output, countOnly)
                    return
                }
                write(output, "{")
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0)write(output, ",")
                    quoted(output, key)
                    write(output, ":")
                    render(item, output, countOnly)
                }
                write(output, "}")
            }
            is JsonArray -> {
                write(output, "[")
                value.forEachIndexed { index, item ->
                    if (index > 0)write(output, ",")
                    render(item, output, countOnly)
                }
                write(output, "]")
            }
            else -> write(output, value.toString())
        }
    }

    private fun renderAttachment(
        part: JsonObject,
        reference: String,
        output: OutputStream,
        countOnly: Boolean,
    ) {
        val wire = source ?: error("This model request cannot load attachments.")
        val attachment = wire.metadata(reference)
        inlined += attachment.size
        require(inlined <= MAX_INLINED_BYTES_PER_RUN) { "This turn's attachments exceed the 32MB model input limit. Send fewer files in each message." }
        val type = part["type"]!!.jsonPrimitive.content
        val image = type in listOf("input_image", "image_url")
        require(classifyAttachment(attachment.mimeType) == if (image)AttachmentKind.IMAGE else AttachmentKind.TEXT) { "The attachment's content type changed." }
        write(output, "{\"type\":")
        quoted(output, type)
        if (!image) {
            write(output, ",\"text\":")
            val bytes =
                text.getOrPut(reference) {
                    val content = wire.open(attachment).use { it.readBytes() }
                    require(content.size.toLong() == attachment.size && content.size <= MAX_FILE_BYTES) { "The attached file changed." }
                    JsonPrimitive("Attached file \"${attachment.name}\":\n\n${extractAttachmentText(content)}").toString().toByteArray(Charsets.UTF_8)
                }
            output.write(bytes)
            write(output, "}")
            return
        }
        if (type == "image_url")write(output, ",\"image_url\":{\"url\":\"") else write(output, ",\"image_url\":\"")
        write(output, "data:${attachment.mimeType};base64,")
        if (countOnly) {
            (output as Counter).add(((attachment.size + 2) / 3) * 4)
        } else {
            var sent = 0L
            Base64OutputStream(output, Base64.NO_WRAP or Base64.NO_CLOSE).use { encoded ->
                wire.open(attachment).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0)break
                        sent += size
                        require(sent <= attachment.size) { "The attached image changed." }
                        encoded.write(buffer, 0, size)
                    }
                }
            }
            require(sent == attachment.size) { "The attached image changed." }
        }
        write(output, if (type == "image_url")"\"}}" else "\"}")
    }
}

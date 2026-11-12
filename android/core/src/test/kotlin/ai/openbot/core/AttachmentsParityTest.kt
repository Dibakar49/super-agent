package ai.openbot.core

import kotlinx.serialization.json.*
import kotlin.test.*

class AttachmentsParityTest {
    private fun fixture(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readText() }).jsonArray

    @Test fun `MIME classification and byte sniffing match unchanged TypeScript`() {
        val kinds = mapOf(AttachmentKind.IMAGE to "image", AttachmentKind.TEXT to "text", AttachmentKind.UNSUPPORTED_IMAGE to "unsupported-image", AttachmentKind.UNSUPPORTED to "unsupported")
        for (row in fixture("attachments-typescript-parity.json")) {
            val item = row.jsonObject
            val bytes = item["bytes"]!!.jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
            val claim = item["claim"]!!.jsonPrimitive.content
            val mime = sniffAttachmentMime(bytes, claim)
            assertEquals(item["mime"]!!.jsonPrimitive.content, mime, "claim=$claim, bytes=${bytes.toList()}")
            assertEquals(item["kind"]!!.jsonPrimitive.content, kinds[classifyAttachment(mime)])
            assertEquals(item["namesNoFormat"]!!.jsonPrimitive.boolean, namesNoFormat(claim))
            assertEquals(item["mediaType"]!!.jsonPrimitive.content, mediaTypeOf(claim))
        }
    }

    @Test fun `text truncation and filenames preserve original Unicode boundaries and wording`() {
        for (row in fixture("attachment-text-typescript-parity.json")) {
            val item = row.jsonObject
            assertEquals(item["expected"]!!.jsonPrimitive.content, extractAttachmentText(item["text"]!!.jsonPrimitive.content.toByteArray(Charsets.UTF_8)))
        }
        for (row in fixture("attachment-name-typescript-parity.json")) {
            val item = row.jsonObject
            assertEquals(item["expected"]!!.jsonPrimitive.content, attachmentFilename(item["name"]!!.jsonPrimitive.content))
        }
    }

    @Test fun `original file count size and paste rules stay explicit`() {
        assertEquals(8, MAX_ATTACHMENTS_PER_MESSAGE)
        assertEquals(32 * 1024 * 1024, MAX_INLINED_BYTES_PER_RUN)
        validateAttachment("photo.png", "image/png", MAX_IMAGE_BYTES.toLong())
        assertFails { validateAttachment("photo.png", "image/png", MAX_IMAGE_BYTES + 1L) }
        validateAttachment("notes.txt", "text/plain", MAX_FILE_BYTES.toLong())
        assertFails { validateAttachment("notes.txt", "text/plain", MAX_FILE_BYTES + 1L) }
        assertFails { validateAttachment("drawing.svg", "image/svg+xml", 32) }
        assertFalse(mayBeTruncatedForModel(120000))
        assertTrue(mayBeTruncatedForModel(120001))
        assertFalse(shouldClaimPaste(listOf(AttachmentKind.IMAGE), "ordinary text"))
        assertTrue(shouldClaimPaste(listOf(AttachmentKind.IMAGE), ""))
    }
}

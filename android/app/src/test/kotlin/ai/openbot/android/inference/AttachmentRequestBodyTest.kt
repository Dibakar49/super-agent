package ai.openbot.android.inference

import ai.openbot.android.data.*
import ai.openbot.core.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentRequestBodyTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @BeforeTest fun reset() {
        context.deleteDatabase("openbot.db")
        File(context.filesDir, "attachments").deleteRecursively()
    }

    @AfterTest fun cleanup() {
        context.deleteDatabase("openbot.db")
        File(context.filesDir, "attachments").deleteRecursively()
    }

    private fun bind(
        store: LocalStore,
        files: AttachmentStore,
        channel: String,
        file: LocalAttachment,
        index: Int,
    ) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            store.saveMessage(LocalMessage("u$index", channel, "user", "Read the file", Instant.now().toString()))
            files.bind(listOf(file.id), requireNotNull(file.stageKey), channel, "u$index")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Test fun `both provider styles stream exact image bytes and original extracted text with fixed lengths`() {
        LocalStore(context).use { store ->
            val files = AttachmentStore(context, store)
            val channel = store.newConversation(store.bots().single().id)
            val data = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)
            val image = files.stage("draft", "photo.png", "image/png", ByteArrayInputStream(data))
            val document = files.stage("draft", "notes.txt", "text/plain", ByteArrayInputStream("A note 😀\nSecond line".toByteArray()))
            bind(store, files, channel.id, image, 1)
            bind(store, files, channel.id, document, 2)
            val source = AttachmentWireSource(context, store)
            source.authorize(channel.id, listOf(image, document))
            for (responses in listOf(true, false)) {
                val parts =
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", if (responses)"input_text" else "text")
                                put("text", attachmentReference(channel.id, document.id))
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", if (responses)"input_image" else "image_url")
                                if (responses)put("image_url", attachmentReference(channel.id, image.id)) else put("image_url", buildJsonObject { put("url", attachmentReference(channel.id, image.id)) })
                            },
                        )
                    }
                val request =
                    buildJsonObject {
                        put(
                            if (responses)"input" else "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", parts)
                                    },
                                )
                            },
                        )
                    }
                val body = AttachmentRequestBody(request, source)
                val output = ByteArrayOutputStream()
                val size = body.byteCount()
                body.writeTo(output)
                assertEquals(size, output.size().toLong())
                // Closing the base64 stream must leave the JSON stream open for later fields.
                val wire = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
                val content = wire[if (responses)"input" else "messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
                assertEquals("Attached file \"notes.txt\":\n\nA note 😀\nSecond line", content[0].jsonObject["text"]!!.jsonPrimitive.content)
                val encoded =
                    if (responses) {
                        content[1].jsonObject["image_url"]!!.jsonPrimitive.content
                    } else {
                        content[1]
                            .jsonObject["image_url"]!!
                            .jsonObject["url"]!!
                            .jsonPrimitive.content
                    }
                assertEquals(
                    "data:image/png;base64," +
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(data),
                    encoded,
                )
                assertFalse(output.toString("UTF-8").contains("openbot-attachment:"))
            }
        }
    }

    @Test fun `arbitrary attachment references cannot read another conversation or an unselected file`() {
        LocalStore(context).use { store ->
            val files = AttachmentStore(context, store)
            val first = store.newConversation(store.bots().single().id)
            val second = store.newConversation(store.bots().single().id)
            val file = files.stage("draft", "private.txt", "text/plain", ByteArrayInputStream("Private".toByteArray()))
            bind(store, files, second.id, file, 1)
            val request =
                buildJsonObject {
                    put("type", "input_text")
                    put("text", attachmentReference(second.id, file.id))
                }
            assertFails { AttachmentRequestBody(request).byteCount() }
            val source = AttachmentWireSource(context, store)
            source.authorize(first.id, listOf(file))
            assertFails { AttachmentRequestBody(request, source).byteCount() }
            source.authorize(second.id, emptyList())
            assertFails { AttachmentRequestBody(request, source).byteCount() }
            val plain =
                buildJsonObject {
                    put("model", "fixture")
                    put("input", "Text and 😀 stay intact")
                }
            val output = ByteArrayOutputStream()
            val body = AttachmentRequestBody(plain)
            body.writeTo(output)
            assertEquals(plain.toString(), output.toString("UTF-8"))
            assertEquals(output.size().toLong(), body.byteCount())
        }
    }
}

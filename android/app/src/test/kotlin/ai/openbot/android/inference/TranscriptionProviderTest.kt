package ai.openbot.android.inference

import ai.openbot.android.data.*
import ai.openbot.core.*
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private class Connection(
        url: URL,
        private val status: Int,
        private val body: String,
    ) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var closed = false

        override fun getOutputStream(): OutputStream = sent

        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())

        override fun getResponseCode() = status

        override fun connect() {}

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun getCipherSuite() = "fixture"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()

        fun expectedLength() = fixedContentLengthLong
    }

    private val configuration =
        object : ModelConfiguration {
            override fun settings() = ProviderSettings(baseUrl = "https://voice.example/v1", model = "speech-model")

            override fun hasKey() = true

            override fun apiKey() = "transcription-fixture-key"
        }

    private fun audio() = DictationAudio(File.createTempFile("transcription-fixture-", ".mp4").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5)) })

    @Test fun `transcription streams original multipart fields only to the configured HTTPS destination`() =
        runBlocking<Unit> {
            val audio = audio()
            try {
                var connection: Connection? = null
                val provider =
                    TranscriptionProvider(configuration) { uri ->
                        assertEquals("https://voice.example/v1/audio/transcriptions", uri.toString())
                        Connection(uri.toURL(), 200, """{"text":" spoken words "}""").also { connection = it }
                    }
                assertEquals(" spoken words ", provider.transcribe(audio))
                val request = requireNotNull(connection)
                assertEquals("POST", request.requestMethod)
                assertFalse(request.instanceFollowRedirects)
                assertEquals("Bearer transcription-fixture-key", request.getRequestProperty("Authorization"))
                assertEquals(request.sent.size().toLong(), request.expectedLength())
                assertTrue(request.closed)
                val body = request.sent.toString("ISO-8859-1")
                assertContains(body, "name=\"model\"\r\n\r\nspeech-model\r\n")
                assertContains(body, "name=\"file\"; filename=\"recording.mp4\"")
                assertContains(body, "Content-Type: audio/mp4")
                assertFalse(body.contains("transcription-fixture-key"))
            } finally {
                audio.file.delete()
            }
        }

    @Test fun `redirects and upstream errors are final safe errors without forwarding the recording`() =
        runBlocking<Unit> {
            val audio = audio()
            try {
                for (status in listOf(302, 401, 429, 500)) {
                    var calls = 0
                    val provider =
                        TranscriptionProvider(configuration) { uri ->
                            calls++
                            Connection(uri.toURL(), status, "sensitive upstream text")
                        }
                    val failure = assertFails { provider.transcribe(audio) }
                    assertIs<TranscriptionError>(failure)
                    assertFalse(failure.message.orEmpty().contains("sensitive upstream"))
                    assertEquals(1, calls)
                }
                assertFails { transcriptionEndpoint("http://voice.example/v1", "speech") }
                assertFails { transcriptionEndpoint("https://voice.example/v1?key=secret", "speech") }
                assertFails { transcriptionEndpoint("https://voice.example/v1", "speech\r\nheader") }
            } finally {
                audio.file.delete()
            }
        }

    @Test fun `credential failures cannot expose raw settings errors or upload audio`() =
        runBlocking<Unit> {
            val audio = audio()
            val broken =
                object : ModelConfiguration {
                    override fun settings(): ProviderSettings = error("sensitive fixture details")

                    override fun apiKey(): String? = error("must not read")

                    override fun hasKey() = true
                }
            try {
                val provider = TranscriptionProvider(broken) { error("No connection may be opened") }
                val failure = assertFails { provider.transcribe(audio) }
                assertIs<TranscriptionError>(failure)
                assertEquals("The transcription settings could not be opened. Save them again in Dictation settings.", failure.message)
            } finally {
                audio.file.delete()
            }
        }

    @Test fun `voice configuration has its own credential namespace and turning it off preserves chat settings`() {
        val model = context.getSharedPreferences("model-settings", 0)
        val voice = context.getSharedPreferences("transcription-settings", 0)
        model
            .edit()
            .clear()
            .putString("encrypted-key", "chat-only-fixture")
            .commit()
        voice.edit().clear().commit()
        try {
            val store = TranscriptionSettingsStore(context)
            assertFalse(store.configured())
            assertFalse(store.hasKey())
            assertNull(store.apiKey())
            store.save("https://voice.example/v1", "speech-model", null, false)
            assertTrue(store.configured())
            assertEquals("speech-model", store.settings().model)
            assertFalse(store.hasKey())
            assertEquals("chat-only-fixture", model.getString("encrypted-key", null))
            store.clear()
            assertFalse(store.configured())
            assertEquals("chat-only-fixture", model.getString("encrypted-key", null))
        } finally {
            model.edit().clear().commit()
            voice.edit().clear().commit()
        }
    }
}

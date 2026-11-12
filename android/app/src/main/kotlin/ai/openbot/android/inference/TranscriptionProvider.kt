package ai.openbot.android.inference

import ai.openbot.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

fun transcriptionEndpoint(
    baseUrl: String,
    model: String,
): URI {
    providerEndpoint(ProviderSettings(baseUrl = baseUrl, model = model))
    require(model.none { it == '\r' || it == '\n' }) { "Enter a single-line transcription model name." }
    return URI(baseUrl.trim().trimEnd('/') + "/audio/transcriptions")
}

class TranscriptionError(
    message: String,
) : IllegalStateException(message)

class TranscriptionBody(
    private val audio: DictationAudio,
    model: String,
    val boundary: String = "OpenBot-" + UUID.randomUUID().toString(),
) {
    private val header: ByteArray
    private val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
    val length: Long

    init {
        require(boundary.matches(Regex("[A-Za-z0-9-]{1,100}")))
        val type = dictationMediaType(audio.mimeType)
        val extension = dictationExtension(type) ?: error("This recording format is not supported. Use WebM, MP4, or WAV audio.")
        require(audio.file.length() in 1..MAX_DICTATION_BYTES.toLong()) { "The recording is empty or too large. Please record again." }
        header =
            (
                "--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n" +
                    "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.$extension\"\r\nContent-Type: $type\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        length = header.size.toLong() + audio.file.length() + footer.size
    }

    fun writeTo(output: OutputStream) {
        output.write(header)
        var sent = 0L
        audio.file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0)break
                sent += count
                require(sent <= MAX_DICTATION_BYTES) { "The recording is too large." }
                output.write(buffer, 0, count)
            }
        }
        require(header.size + sent + footer.size == length) { "The recording changed before transcription." }
        output.write(footer)
    }
}

class TranscriptionProvider(
    private val configuration: ModelConfiguration,
    private val open: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection },
) {
    suspend fun transcribe(audio: DictationAudio): String =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val (settings, key) =
                    try {
                        configuration.snapshot()
                    } catch (_: Exception) {
                        throw TranscriptionError("The transcription settings could not be opened. Save them again in Dictation settings.")
                    }
                val endpoint = transcriptionEndpoint(settings.baseUrl, settings.model)
                val body = TranscriptionBody(audio, settings.model)
                require(key == null || key.none { it == '\r' || it == '\n' }) { "Save a valid transcription provider key." }
                val connection = open(endpoint)
                val cancellation =
                    launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            connection.disconnect()
                        }
                    }
                try {
                    connection.requestMethod = "POST"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20_000
                    connection.readTimeout = DICTATION_TIMEOUT_MS.toInt()
                    key?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=${body.boundary}")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.length)
                    connection.outputStream.use { body.writeTo(it) }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        throw TranscriptionError(
                            when (status) {
                                401, 403 -> "The transcription provider refused the key. Check it in Dictation settings."
                                429 -> "The transcription service is busy. Please retry shortly."
                                in 300..399 -> "The transcription service redirected the request. Save its final HTTPS URL in Dictation settings."
                                else -> "Couldn't process this recording. Check Dictation settings and try again."
                            },
                        )
                    }
                    val response =
                        connection.inputStream.use { input ->
                            val result = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0)break
                                if (result.size() + count > 1024 * 1024)throw TranscriptionError("The transcription service returned an invalid response. Please retry.")
                                result.write(buffer, 0, count)
                            }
                            result.toString("UTF-8")
                        }
                    val parsed = runCatching { Json.parseToJsonElement(response).jsonObject["text"] as? JsonPrimitive }.getOrNull()
                    if (parsed == null || !parsed.isString)throw TranscriptionError("The transcription service returned an invalid response. Please retry.")
                    parsed.content
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: TranscriptionError) {
                    throw failure
                } catch (_: Exception) {
                    throw TranscriptionError("Couldn't reach the transcription service. Try again.")
                } finally {
                    cancellation.cancel()
                    connection.disconnect()
                }
            }
        }
}

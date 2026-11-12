package ai.openbot.android.inference

import ai.openbot.core.*
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

fun interface ProviderTransport {
    suspend fun request(settings: ProviderSettings, key: String, request: JsonObject): JsonObject
    suspend fun stream(settings: ProviderSettings, key: String, request: JsonObject, onText: suspend (String) -> Unit): JsonObject = request(settings, key, request)
}

/** HTTPS only; never redirects or retries a POST, including a turn containing tool results. */
class ProviderModel(private val attachments: AttachmentWireSource? = null, client: OkHttpClient = sharedClient): ProviderTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun generate(settings: ProviderSettings, key: String, prompt: String): String = providerAnswer(settings.apiStyle, request(settings, key, providerRequest(settings, prompt)))

    override suspend fun request(settings: ProviderSettings, key: String, request: JsonObject): JsonObject = exchange(settings, key, request, null)
    override suspend fun stream(settings: ProviderSettings, key: String, request: JsonObject, onText: suspend (String) -> Unit): JsonObject = exchange(settings, key, request, onText)

    private suspend fun exchange(settings: ProviderSettings, key: String, request: JsonObject, onText: (suspend (String) -> Unit)?): JsonObject = withContext(Dispatchers.IO) { coroutineScope {
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' }) { "Save a valid model provider key in Model settings first." }
        val payload = if (onText == null) request else JsonObject(request + ("stream" to JsonPrimitive(true)))
        val body = AttachmentRequestBody(payload, attachments)
        val length = body.byteCount()
        val requestBody = object: RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) = body.writeTo(sink.outputStream())
            override fun isOneShot() = true
        }
        val wire = Request.Builder().url(providerEndpoint(settings).toString())
            .header("Authorization", "Bearer $key")
            .header("Accept", if (onText == null) "application/json" else "text/event-stream, application/json")
            .post(requestBody).build()
        val call = client.newCall(wire)
        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        401, 403 -> "The model provider refused the saved key. Check it in Model settings."
                        429 -> "The model provider is rate limited or out of quota. Try later."
                        in 300..399 -> "The model API redirected the request. Enter its final HTTPS endpoint; keys are never forwarded to redirects."
                        else -> "The model API request failed (HTTP ${response.code}). Check the API URL, model name and API style."
                    }
                }
                val responseBody = response.body ?: error("The model API returned no response.")
                BoundedResponse(responseBody.byteStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                    if (onText == null || responseBody.contentType()?.subtype != "event-stream") {
                        return@coroutineScope runCatching { Json.parseToJsonElement(reader.readText()).jsonObject }.getOrElse { error("The model API returned an invalid JSON response.") }
                    }
                    val events = ServerSentEvents()
                    val stream = ProviderStream(settings.apiStyle)
                    var lastText = ""
                    var lastUpdate = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = reader.readLine() ?: break
                        val event = events.line(line) ?: continue
                        val complete = stream.accept(event)
                        val text = stream.visibleText
                        val now = System.nanoTime()
                        if (text != lastText && (lastText.isEmpty() || complete || now - lastUpdate >= 100_000_000)) {
                            onText(text); lastText = text; lastUpdate = now
                        }
                        if (complete) {
                            if (text != lastText) onText(text)
                            break
                        }
                    }
                    stream.finish()
                }
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally { watcher.cancel(); call.cancel() }
    } }

    private class BoundedResponse(input: InputStream): FilterInputStream(input) {
        private var count = 0L
        private fun account(size: Int): Int {
            if (size > 0) count += size
            check(count <= 2 * 1024 * 1024) { "The model API response was too large." }
            return size
        }
        override fun read(): Int = super.read().also { if (it >= 0) account(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = account(`in`.read(buffer, offset, length))
    }

    companion object {
        private val sharedClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    }
}

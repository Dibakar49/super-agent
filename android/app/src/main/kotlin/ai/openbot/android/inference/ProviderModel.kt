package ai.openbot.android.inference

import ai.openbot.core.*
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun interface ProviderTransport { suspend fun request(settings:ProviderSettings,key:String,request:kotlinx.serialization.json.JsonObject):kotlinx.serialization.json.JsonObject }

class ProviderModel(private val attachments:AttachmentWireSource?=null):ProviderTransport {
    suspend fun generate(settings: ProviderSettings, key: String, prompt: String): String = providerAnswer(settings.apiStyle, request(settings,key,providerRequest(settings,prompt)))

    override suspend fun request(settings:ProviderSettings,key:String,request: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject = withContext(Dispatchers.IO) {coroutineScope {
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' }) { "Save a valid model provider key in Model settings first." }
        val body=AttachmentRequestBody(request,attachments)
        val length=body.byteCount()
        val connection = providerEndpoint(settings).toURL().openConnection() as HttpsURLConnection
        val cancellationWatcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {connection.disconnect()}
        }
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use { body.writeTo(it) }
            val status = connection.responseCode
            check(status in 200..299) {
                when (status) {
                    401, 403 -> "The model provider refused the saved key. Check it in Model settings."
                    429 -> "The model provider is rate limited or out of quota. Try later."
                    in 300..399 -> "The model API redirected the request. Enter its final HTTPS endpoint; keys are never forwarded to redirects."
                    else -> "The model API request failed (HTTP $status). Check the API URL, model name and API style."
                }
            }
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "The model API response was too large." }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            Json.parseToJsonElement(response).jsonObject
        } finally { cancellationWatcher.cancel();connection.disconnect() }
    }}
}

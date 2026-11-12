package ai.openbot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable enum class ModelMode { PROVIDER_API, ON_DEVICE }

@Serializable enum class ApiStyle { RESPONSES, CHAT_COMPLETIONS }

@Serializable data class ProviderSettings(
    val mode: ModelMode = ModelMode.PROVIDER_API,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4.1-mini",
    val apiStyle: ApiStyle = ApiStyle.RESPONSES,
)

/** Provider calls run from the phone's local backend, never from a remote OpenBot server. */
fun providerEndpoint(settings: ProviderSettings): URI {
    val uri = runCatching { URI(settings.baseUrl.trim()) }.getOrElse { error("Enter a valid model API URL.") }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Use an HTTPS model API URL without credentials, query parameters, or a fragment."
    }
    require(settings.model.isNotBlank() && settings.model.length <= 200) { "Enter a model name." }
    val suffix = if (settings.apiStyle == ApiStyle.RESPONSES) "responses" else "chat/completions"
    return URI(uri.toASCIIString().trimEnd('/') + "/" + suffix)
}

fun providerRequest(
    settings: ProviderSettings,
    prompt: String,
): JsonObject {
    providerEndpoint(settings)
    return buildJsonObject {
        put("model", settings.model.trim())
        if (settings.apiStyle == ApiStyle.RESPONSES) {
            put("input", prompt)
            put("max_output_tokens", 1024)
            put("store", false)
        } else {
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }
    }
}

fun providerAnswer(
    style: ApiStyle,
    response: JsonObject,
): String {
    val answer =
        if (style == ApiStyle.CHAT_COMPLETIONS) {
            val message = (response["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            (message?.get("content") as? JsonPrimitive)?.contentOrNull
        } else {
            val status = (response["status"] as? JsonPrimitive)?.contentOrNull
            check(status == null || status == "completed") { "The model response was ${status ?: "incomplete"}; no complete answer was saved." }
            (response["output"] as? JsonArray)
                .orEmpty()
                .flatMap { output ->
                    ((output as? JsonObject)?.get("content") as? JsonArray).orEmpty()
                }.mapNotNull { content ->
                    (content as? JsonObject)
                        ?.takeIf { (it["type"] as? JsonPrimitive)?.contentOrNull == "output_text" }
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.joinToString("\n")
        }
    return answer?.takeIf { it.isNotBlank() } ?: error("The model returned no text answer.")
}

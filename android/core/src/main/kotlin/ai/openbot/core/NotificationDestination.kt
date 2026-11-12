package ai.openbot.core

import java.net.URI
import java.net.URLDecoder

sealed interface NotificationDestination {
    data object NeedsYou : NotificationDestination

    data class Conversation(
        val channelId: String,
        val requestId: String? = null,
    ) : NotificationDestination

    data class Approvals(
        val channelId: String,
        val requestId: String? = null,
    ) : NotificationDestination
}

/** Strict route parser translated from mobile/src/links.ts; it never opens arbitrary URIs. */
fun notificationDestination(value: String?): NotificationDestination? {
    val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (!uri.scheme.equals("openbotmobile", ignoreCase = true) || !uri.rawUserInfo.isNullOrEmpty() ||
        !uri.rawFragment.isNullOrEmpty() || uri.port != -1 || uri.path !in listOf("", "/")
    ) {
        return null
    }
    val host = uri.host?.lowercase() ?: return null
    if (host == "needs-you") return if (uri.rawQuery.isNullOrEmpty()) NotificationDestination.NeedsYou else null
    if (host !in listOf("conversation", "approvals")) return null
    val params =
        runCatching {
            uri.rawQuery.orEmpty().split('&').map {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
        }.getOrNull() ?: return null
    val channel = params.firstOrNull { it.first == "channelId" }?.second ?: return null
    val request = params.firstOrNull { it.first == "requestId" }?.second?.takeIf { it.isNotEmpty() }
    if (channel.isEmpty() || channel.length > 200 || (request?.length ?: 0) > 200) return null
    return if (host == "conversation") {
        NotificationDestination.Conversation(channel, request)
    } else {
        NotificationDestination.Approvals(channel, request)
    }
}

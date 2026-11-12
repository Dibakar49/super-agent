package ai.openbot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class LocalToolEvent(
    val id: String,
    val turnId: String,
    val channelId: String,
    val callId: String,
    val name: String,
    val arguments: JsonObject,
    val result: String,
    val failed: Boolean,
    val createdAt: String,
    val displayName: String? = null,
)

package ai.openbot.core

/** Same visible fields as app-sidebar.tsx: never claim to search unloaded message history. */
fun matchingConversations(channels: List<LocalConversation>, search: String): List<LocalConversation> {
    val term = search.trim().lowercase()
    return if (term.isEmpty()) channels else channels.filter {
        listOf(it.name, it.summary, it.opening, it.lastMessage).any { text -> text?.lowercase()?.contains(term) == true }
    }
}
fun pinnedConversationsFirst(channels: List<LocalConversation>) = channels.filter { it.pinned } + channels.filterNot { it.pinned }
fun conversationTitle(channel: LocalConversation): String =
    sequenceOf(channel.summary, channel.opening, channel.lastMessage, channel.name).firstOrNull { !it.isNullOrBlank() }.orEmpty().replace(Regex("\\s+"), " ")
fun conversationUnread(channel: LocalConversation, openId: String?): Boolean = channel.id != openId &&
    channel.lastMessageAgentId != null && channel.lastMessageAt != null &&
    (channel.lastReadAt == null || channel.lastMessageAt > channel.lastReadAt)

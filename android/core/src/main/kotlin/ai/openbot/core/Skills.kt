package ai.openbot.core

import kotlinx.serialization.Serializable

@Serializable data class LocalSkill(val slug: String, val title: String, val summary: String,
    val instructions: String, val tools: List<String> = emptyList())
@Serializable data class LocalPreferences(val name: String = "", val standingInstructions: String = "",
    val darkTheme: Boolean? = null, val messageListEmphasis: String = "thread",val notificationsEnabled:Boolean=false)

/** Limits and command pattern from app/src/lib/skills/form.ts. */
fun validateSkill(skill: LocalSkill) {
    require(Regex("^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$").matches(skill.slug)) { "Lower-case letters, numbers and hyphens, 2 to 40 characters." }
    require(skill.title.isNotBlank() && skill.title.trim().length <= 120) { "A title of 1–120 characters is required." }
    require(skill.summary.trim().length <= 200) { "The one-liner must be 200 characters or fewer." }
    require(skill.instructions.isNotBlank()) { "Instructions are required. They are what the Bot follows." }
}
fun leadingSkillCommand(text: String): String? = Regex("^\\s*/([a-z0-9]+(?:-[a-z0-9]+)*)(?=\\s|$)").find(text)?.groupValues?.get(1)
fun pickedSkillGuidance(text: String, held: List<LocalSkill>): String? {
    val command = leadingSkillCommand(text) ?: return null
    val skill = held.find { it.slug == command } ?: return null
    val instructions = skill.instructions.trim().takeIf { it.isNotEmpty() } ?: return null
    return "The person picked the skill \"${skill.title.ifEmpty { skill.slug }}\" (/${skill.slug}) for their latest message. Follow its instructions for this reply:\n\n$instructions"
}
fun firstConversationGreeting(name: String?, noConversations: Boolean): String =
    if (!noConversations || name.isNullOrBlank()) "Start a conversation" else "Hi ${name.trim()}, what would you like to work on?"

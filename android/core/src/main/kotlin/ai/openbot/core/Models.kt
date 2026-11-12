package ai.openbot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

// Wire contracts translated from mobile/src/api.ts. Local-only state lives separately below.
@Serializable data class Conversation(val id: String, val name: String, val agentIds: List<String>, val active: Boolean)
@Serializable data class TranscriptMessage(val id: String, val role: String, val content: JsonElement? = null, val toolCalls: List<JsonElement>? = null)
@Serializable data class Bot(val id: String, val name: String, val title: String? = null, val roleDescription: String? = null, val mine: Boolean, val canManage: Boolean)
@Serializable data class BotLifecycle(val paused: Boolean, val pausedAt: String? = null)
@Serializable data class CheckInItem(val id: String, val agentId: String, val title: String, val detail: String, val sourceApp: String? = null, val sourceLink: String? = null, val createdAt: String)
@Serializable data class StartedItem(val task: StartedTask) {
    @Serializable data class StartedTask(val id: String, val channelId: String)
}
@Serializable enum class TaskStatus {
    @SerialName("needs_input") NEEDS_INPUT,
    @SerialName("working") WORKING,
    @SerialName("resolved") RESOLVED,
    @SerialName("failed") FAILED,
    @SerialName("ready_for_review") READY_FOR_REVIEW
}
@Serializable data class BotTask(val id: String, val agentId: String, val channelId: String, val title: String, val status: TaskStatus, val failure: String? = null, val next: String? = null, val updatedAt: String)
@Serializable enum class RoutineStatus {
    @SerialName("running") RUNNING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("skipped") SKIPPED,
    @SerialName("waiting") WAITING
}
@Serializable data class Routine(val id: String, val agentId: String, val schedule: String, val timezone: String, val instruction: String, val channel: RoutineChannel, val enabled: Boolean, val nextRunAt: String, val lastRun: LastRun? = null)
@Serializable data class RoutineChannel(val id: String, val name: String? = null, val gone: Boolean)
@Serializable data class LastRun(val status: RoutineStatus? = null, val at: String? = null)
@Serializable data class RoutineRun(val id: String, val status: RoutineStatus, val startedAt: String, val finishedAt: String? = null, val error: String? = null)
@Serializable data class Memory(val id: String, val content: String, val provenance: String, val enabled: Boolean, val reviewState: ReviewState, val formedBy: FormedBy, val updatedAt: String, val formedByAgentId:String?=null, val sourceApp:String?=null, val sourceLink:String?=null, val observedAt:String?=null)
@Serializable enum class ReviewState {
    @SerialName("unreviewed") UNREVIEWED,
    @SerialName("confirmed") CONFIRMED,
    @SerialName("edited") EDITED
}
@Serializable enum class FormedBy {
    @SerialName("person") PERSON,
    @SerialName("import") IMPORT,
    @SerialName("bot") BOT
}
@Serializable enum class RuleBehaviour(val label: String) {
    @SerialName("allow") ALLOW("Take action without asking"),
    @SerialName("pre_approved") PRE_APPROVED("Take action if pre-approved"),
    @SerialName("ask") ASK("Ask before taking action"),
    @SerialName("hand_off") HAND_OFF("Hand off to you")
}
@Serializable data class ApprovalRule(val id: String, val botId: String, val toolRef: String, val effect: String, val scope: String, val behaviour: RuleBehaviour)
@Serializable data class ApprovalPreferences(val enabled: Boolean, val autoReview: Boolean)
@Serializable data class TeamApprovalSettings(val enforceAutoReview: Boolean, val customRulesEnabled: Boolean)
@Serializable data class ApprovalSettings(val enabled: Boolean, val rules: List<ApprovalRule>, val teamRules: List<ApprovalRule>, val preferences: ApprovalPreferences? = null, val team: TeamApprovalSettings? = null)
@Serializable data class ApprovalPolicy(val source: String, val reason: String)
@Serializable data class ApprovalAction(val botId: String, val toolRef: String, val effect: String, val scope: String, val args: JsonElement, val target: JsonElement? = null, val policy: ApprovalPolicy? = null)
@Serializable data class ApprovalRequest(val id: String, val status: String, val botId: String? = null, val toolRef: String? = null, val effect: String? = null, val scope: String? = null, val preview: JsonElement? = null, val action: ApprovalAction? = null)
@Serializable data class PersonQuestion(val id: String, val question: String, val botId: String, val why: String? = null, val channelId: String? = null)
@Serializable data class ApprovalInbox(val requests: List<ApprovalRequest>, val questions: List<PersonQuestion>? = null)
@Serializable data class ToolSummary(val ref: String, val description: String)
@Serializable data class SkillSummary(val slug: String, val title: String, val summary: String)
@Serializable data class GrantedPlugins(val tools: List<ToolSummary>, val skills: List<SkillSummary>)
@Serializable data class ControlRequest(val id: String, val status: String, val reason: String)
@Serializable data class ControlState(val holder: String, val requested: Boolean, val transitioning: Boolean, val reason: String? = null, val request: ControlRequest? = null, val secretWanted: String? = null)
@Serializable data class Screenshot(val base64: String, val width: Int, val height: Int, val url: String? = null)

// Android's local data: no network account or remote backend required.
@Serializable data class LocalBot(val id: String, val name: String, val instruction: String, val paused: Boolean = false,
    val title: String = "OpenBot", val pinned: Boolean = false, val hidden: Boolean = false, val visibility: String = "private")
@Serializable data class LocalConversation(val id: String, val botId: String, val name: String, val createdAt: String,
    val summary: String? = null, val opening: String? = null, val lastMessage: String? = null,
    val lastMessageAgentId: String? = null, val lastMessageAt: String? = null,
    val pinned: Boolean = false, val lastReadAt: String? = null)
@Serializable data class LocalMessage(val id: String, val conversationId: String, val role: String, val text: String, val createdAt: String, val state: MessageState = MessageState.COMPLETE,val systemLabel:String?=null,val displayText:String?=null,val attachmentIds:List<String> = emptyList())
@Serializable enum class MessageState { COMPLETE, GENERATING, FAILED, INTERRUPTED, WAITING }

val runLabels = mapOf(
    RoutineStatus.RUNNING to "Running", RoutineStatus.SUCCEEDED to "Succeeded",
    RoutineStatus.FAILED to "Failed", RoutineStatus.SKIPPED to "Skipped",
    RoutineStatus.WAITING to "Waiting for you"
)
const val ADD_ROUTINE_DRAFT = "Set up a routine to "

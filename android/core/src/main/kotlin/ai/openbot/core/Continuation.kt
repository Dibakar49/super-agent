package ai.openbot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable enum class ContinuationPhase { WAITING, QUEUED, RUNNING, COMPLETE, INTERRUPTED }

@Serializable enum class LocalActionStatus { PENDING, APPROVED, DENIED, HANDLED, EXECUTING, EXECUTED, FAILED, UNCERTAIN, CANCELLED }

@Serializable data class PendingLocalAction(
    val id: String,
    val turnId: String,
    val channelId: String,
    val botId: String,
    val connectionId: String,
    val connectionUrl: String,
    val credentialRef: String?,
    val tool: McpTool,
    val call: ToolCall,
    val createdAt: String,
    val status: LocalActionStatus = LocalActionStatus.PENDING,
    val standingRule: Boolean = false,
    val automatic: Boolean = false,
    val policy: LocalPolicyOutcome? = null,
    val connectionName: String? = null,
    val goalRunId: String? = null,
    val routineRunId: String? = null,
)

/** All continuation data is local; provider keys and connection credentials are never serialized. */
@Serializable data class AgentCheckpoint(
    val turnId: String,
    val channelId: String,
    val botId: String,
    val settings: ProviderSettings,
    val tools: List<AgentTool>,
    val history: JsonArray,
    val round: AgentRound,
    val results: List<String>,
    val approvalId: String,
    val createdAt: String,
    val phase: ContinuationPhase = ContinuationPhase.WAITING,
    val routineRunId: String? = null,
    val roundCount: Int = 0,
    val goalRunId: String? = null,
    val questionId: String? = null,
)

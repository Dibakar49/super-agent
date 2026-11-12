package ai.openbot.android.inference

import ai.openbot.android.data.*
import ai.openbot.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

internal class GoalTools(
    private val store: LocalStore,
) {
    private val goals = GoalStore(store)
    val definitions: List<AgentTool> by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/goal-tools.json")).bufferedReader().use { it.readText() }).jsonArray.map { item ->
            val obj = item.jsonObject
            AgentTool(obj["name"]!!.jsonPrimitive.content, obj["description"]!!.jsonPrimitive.content, obj["inputSchema"]!!.jsonObject)
        }
    }

    fun execute(
        call: ToolCall,
        botId: String,
        channelId: String,
        goalRunId: String?,
        routineRunId: String? = null,
    ): String {
        val args = call.arguments

        fun string(
            obj: JsonObject,
            name: String,
        ) = obj[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        fun required(name: String) = string(args, name) ?: error("Provide $name.")

        fun owned(id: String): LocalGoal = goals.get(id).also { require(it.botId == botId) { "This goal could not be found." } }

        fun subscriptions(obj: JsonObject): List<GoalSubscription>? =
            (obj["subscriptions"] as? JsonArray)?.map { item ->
                val entry = item.jsonObject
                GoalSubscription(entry["source"]!!.jsonPrimitive.content, entry["eventType"]!!.jsonPrimitive.content)
            }

        fun result(goal: LocalGoal) = Json.encodeToString(goal)
        return when (call.name) {
            "create_goal" -> {
                val now = Instant.now().toString()
                result(
                    goals.save(
                        LocalGoal(
                            UUID.randomUUID().toString(),
                            botId,
                            string(args, "channelId") ?: channelId,
                            required("title"),
                            required("instruction"),
                            required("successCriteria"),
                            recurring = args["recurring"]?.jsonPrimitive?.booleanOrNull ?: false,
                            subscriptions = subscriptions(args).orEmpty(),
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ),
                )
            }
            "list_goals" -> Json.encodeToString(goals.list().filter { it.botId == botId })
            "update_goal" -> {
                val old = owned(required("id"))
                val patch = args["patch"]?.jsonObject ?: error("Provide patch.")
                require(patch.keys.all { it in setOf("title", "instruction", "successCriteria", "recurring", "subscriptions") }) { "The goal patch contains an unknown field." }
                result(
                    goals.save(
                        old.copy(
                            title = string(patch, "title") ?: old.title,
                            instruction = string(patch, "instruction") ?: old.instruction,
                            successCriteria = string(patch, "successCriteria") ?: old.successCriteria,
                            recurring = patch["recurring"]?.jsonPrimitive?.booleanOrNull ?: old.recurring,
                            subscriptions = subscriptions(patch) ?: old.subscriptions,
                            updatedAt = Instant.now().toString(),
                        ),
                    ),
                )
            }
            "run_goal" -> {
                val goal = owned(required("id"))
                require(goalRunId?.let(goals::run)?.goalId != goal.id) { "This goal is already being worked on in this run. Carry out its instruction and report progress instead of queueing itself." }
                val run = goals.enqueue(goal.id)
                routineRunId?.let(store::routineRun)?.let { firing ->
                    store.routines().find { it.id == firing.routineId && it.botId == botId && it.goalId == null }?.let { store.saveRoutine(it.copy(goalId = goal.id)) }
                }
                Json.encodeToString(run)
            }
            "pause_goal", "resume_goal", "complete_goal" -> {
                val goal = owned(required("id"))
                if (call.name == "complete_goal" && goalRunId != null)goals.repeats(goal.id)?.let { error("This goal repeats because $it, so a run can't complete it. Only the person ends it.") }
                result(
                    goals.transition(
                        goal.id,
                        when (call.name) {
                            "pause_goal" -> GoalStatus.PAUSED
                            "resume_goal" -> GoalStatus.ACTIVE
                            else -> GoalStatus.COMPLETED
                        },
                    ),
                )
            }
            "report_goal_progress" -> {
                val run = goalRunId?.let(goals::run) ?: error("Progress must come from the goal's current run.")
                val goal = owned(required("id"))
                require(run.goalId == goal.id) { "Progress must come from the goal's current run." }
                val complete = args["complete"]?.jsonPrimitive?.booleanOrNull == true
                val repeats = if (complete)goals.repeats(goal.id) else null
                val saved = goals.progress(goal.id, required("summary"), complete && repeats == null, run.id)
                if (repeats == null) {
                    result(saved)
                } else {
                    buildJsonObject {
                        put("goal", Json.encodeToJsonElement(saved))
                        put("note", "Recorded. This goal repeats because $repeats. It stays active; only the person ends it.")
                    }.toString()
                }
            }
            else -> error("This goal tool is not available.")
        }
    }
}

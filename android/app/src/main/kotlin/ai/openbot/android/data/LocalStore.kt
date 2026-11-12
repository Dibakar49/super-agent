package ai.openbot.android.data

import ai.openbot.core.*
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

object LocalDatabaseChanges {
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    @Synchronized fun changed() {
        revision.value = revision.value + 1
    }
}

/** App-private, transactional storage. No server, Docker daemon, or cloud database is used. */
class LocalStore(
    context: Context,
) : SQLiteOpenHelper(context, "openbot.db", null, 11) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    private fun databaseChanged() {
        if (!writableDatabase.inTransaction())LocalDatabaseChanges.changed()
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bots (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, bot_id TEXT NOT NULL REFERENCES bots(id), created_at TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, created_at TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_conversation ON messages(conversation_id, created_at)")
        db.execSQL("CREATE TABLE memories (id TEXT PRIMARY KEY, updated_at TEXT NOT NULL, payload TEXT NOT NULL)")
        val bot = LocalBot(UUID.randomUUID().toString(), "OpenBot", "You are a helpful AI coworker. Answer clearly and honestly. Use only the tools made available for this turn, and never claim an action succeeded without its result. Computer use is not available.")
        db.insertOrThrow(
            "bots",
            null,
            ContentValues().apply {
                put("id", bot.id)
                put("payload", json.encodeToString(bot))
            },
        )
        createCapabilityTables(db)
        createRoutineTables(db)
        createMemoryTombstones(db)
        createToolEvents(db)
        createConnections(db)
        createContinuations(db)
        GoalStore.createTables(db)
        ApprovalRuleStore.createTables(db)
        QuestionStore.createTables(db)
        AttachmentStore.createTables(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 2) createCapabilityTables(db)
        if (oldVersion < 3) createRoutineTables(db)
        if (oldVersion < 4) createMemoryTombstones(db)
        if (oldVersion < 5) createToolEvents(db)
        if (oldVersion < 6) createConnections(db)
        if (oldVersion < 7) createContinuations(db)
        if (oldVersion < 8) {
            GoalStore.createTables(db)
            db.rawQuery("SELECT id,payload FROM bots", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val bot = json.decodeFromString<LocalBot>(cursor.getString(1))
                    if (bot.name == "OpenBot" && bot.instruction == "You are a helpful AI coworker. Answer clearly and honestly. You have no browser, computer-control, or external app tools. Never claim to have taken an action you cannot perform.")db.update("bots", ContentValues().apply { put("payload", json.encodeToString(bot.copy(instruction = "You are a helpful AI coworker. Answer clearly and honestly. Use only the tools made available for this turn, and never claim an action succeeded without its result. Computer use is not available."))) }, "id=?", arrayOf(bot.id))
                }
            }
        }
        if (oldVersion < 11) AttachmentStore.createTables(db)
        if (oldVersion < 10) QuestionStore.createTables(db)
        if (oldVersion < 9) {
            val legacy =
                db.rawQuery("SELECT COUNT(*) FROM connections", null).use {
                    it.moveToFirst()
                    it.getInt(0) > 0
                }
            ApprovalRuleStore.createTables(db, keepLegacyAsk = legacy)
        }
    }

    private fun createContinuations(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE continuations(turn_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE pending_actions(id TEXT PRIMARY KEY,turn_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX pending_actions_turn ON pending_actions(turn_id)")
    }

    fun checkpoints(): List<AgentCheckpoint> = query("SELECT payload FROM continuations ORDER BY rowid")

    fun pendingActions(): List<PendingLocalAction> = query("SELECT payload FROM pending_actions ORDER BY rowid")

    fun saveCheckpoint(checkpoint: AgentCheckpoint) {
        val values = ContentValues().apply { put("payload", json.encodeToString(checkpoint)) }
        if (writableDatabase.update("continuations", values, "turn_id=?", arrayOf(checkpoint.turnId)) == 0) {
            values.put("turn_id", checkpoint.turnId)
            writableDatabase.insertOrThrow("continuations", null, values)
        }
        databaseChanged()
    }

    fun savePendingAction(action: PendingLocalAction) {
        val values = ContentValues().apply { put("payload", json.encodeToString(action)) }
        if (writableDatabase.update("pending_actions", values, "id=?", arrayOf(action.id)) == 0) {
            values.put("id", action.id)
            values.put("turn_id", action.turnId)
            writableDatabase.insertOrThrow("pending_actions", null, values)
        }
        databaseChanged()
    }

    fun waitForApproval(
        checkpoint: AgentCheckpoint,
        action: PendingLocalAction,
    ) {
        require(checkpoint.approvalId == action.id && checkpoint.turnId == action.turnId && checkpoint.channelId == action.channelId)
        require(checkpoint.results.size < checkpoint.round.calls.size && checkpoint.round.calls[checkpoint.results.size] == action.call)
        writableDatabase.beginTransaction()
        try {
            val message = messages(checkpoint.channelId).first { it.id == checkpoint.turnId }
            require(message.role == "assistant" && message.state in listOf(MessageState.GENERATING, MessageState.WAITING))
            savePendingAction(action)
            saveCheckpoint(checkpoint)
            saveMessage(message.copy(state = MessageState.WAITING, text = "Waiting for your approval."))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun decideAction(
        id: String,
        decision: LocalActionStatus,
        always: Boolean = false,
    ): AgentCheckpoint {
        require(decision in listOf(LocalActionStatus.APPROVED, LocalActionStatus.DENIED, LocalActionStatus.HANDLED))
        writableDatabase.beginTransaction()
        try {
            val action = pendingActions().firstOrNull { it.id == id } ?: error("That approval no longer exists.")
            require(action.status == LocalActionStatus.PENDING) { "This approval has already been answered." }
            val handOff = action.policy?.behaviour == PolicyBehaviour.HAND_OFF
            require(!handOff || decision in listOf(LocalActionStatus.HANDLED, LocalActionStatus.DENIED)) { "This action is handed to you. Do it yourself and mark it done, or decline it." }
            require(handOff || decision != LocalActionStatus.HANDLED) { "Only an action handed to you can be marked done by you." }
            if (always) {
                require(decision == LocalActionStatus.APPROVED && offersStandingRule(action.policy?.source) && safetyRequirement(pendingCandidate(action)) == null) { "This action asks every time. Allow this once, or deny it." }
                val candidate = pendingCandidate(action)
                ApprovalRuleStore(this).save(ApprovalRule(UUID.randomUUID().toString(), candidate.botId, candidate.toolRef, candidate.effect, candidate.scope, RuleBehaviour.ALLOW))
            }
            val checkpoint = checkpoints().firstOrNull { it.turnId == action.turnId && it.approvalId == id && it.phase == ContinuationPhase.WAITING } ?: error("This turn can no longer continue. Send a new message to the Bot.")
            checkpoint.goalRunId?.let { runId ->
                val goals = GoalStore(this)
                val run = goals.run(runId) ?: error("This goal run no longer exists.")
                require(goals.get(run.goalId).status == GoalStatus.ACTIVE) { "Resume the goal before answering its waiting run." }
            }
            savePendingAction(action.copy(status = decision, standingRule = always))
            val queued = checkpoint.copy(phase = ContinuationPhase.QUEUED)
            saveCheckpoint(queued)
            writableDatabase.setTransactionSuccessful()
            return queued
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    /** WorkManager owns scheduling. This is a transactional state transition, not another lease. */
    fun claimContinuation(turnId: String): AgentCheckpoint? {
        writableDatabase.beginTransaction()
        try {
            val checkpoint = checkpoints().firstOrNull { it.turnId == turnId && it.phase == ContinuationPhase.QUEUED } ?: return null
            val running = checkpoint.copy(phase = ContinuationPhase.RUNNING)
            saveCheckpoint(running)
            writableDatabase.setTransactionSuccessful()
            return running
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun deleteCheckpoint(turnId: String) {
        writableDatabase.delete("continuations", "turn_id=?", arrayOf(turnId))
        databaseChanged()
    }

    fun beginApprovedAction(id: String): Boolean {
        writableDatabase.beginTransaction()
        try {
            val action = pendingActions().firstOrNull { it.id == id && it.status == LocalActionStatus.APPROVED } ?: return false
            if (bots().none { it.id == action.botId && !it.paused })return false
            if (checkpoints().any { it.turnId == action.turnId && it.phase == ContinuationPhase.INTERRUPTED })return false
            val candidate = pendingCandidate(action)
            val rules = ApprovalRuleStore(this)
            val mandatory = safetyRequirement(candidate)
            if (mandatory?.behaviour == PolicyBehaviour.HAND_OFF || mandatory?.behaviour == PolicyBehaviour.ASK && action.automatic)return false
            if (strictestRule(candidate, emptyList(), rules.rules())?.rule?.behaviour == RuleBehaviour.HAND_OFF)return false
            if (action.standingRule && !rules.standingRuleExists(candidate))return false
            if (action.automatic && action.policy?.behaviour != PolicyBehaviour.ALLOW)return false
            if (action.automatic && !canExecuteAutomatically(candidate, rules.rules(), rules.preferences(), action.policy?.review))return false
            val checkpoint = checkpoints().find { it.turnId == action.turnId }
            // Automatic actions also carry their scope: there may be no waiting checkpoint.
            (action.goalRunId ?: checkpoint?.goalRunId)?.let { runId ->
                val goals = GoalStore(this)
                val run = goals.run(runId) ?: return false
                if (goals.get(run.goalId).status != GoalStatus.ACTIVE || run.status !in listOf(GoalRunStatus.RUNNING, GoalRunStatus.WAITING))return false
            }
            (action.routineRunId ?: checkpoint?.routineRunId)?.let { runId ->
                val run = routineRun(runId) ?: return false
                if (run.status !in listOf(RoutineStatus.RUNNING, RoutineStatus.WAITING) || routines().none { it.id == run.routineId && it.enabled })return false
            }
            savePendingAction(action.copy(status = LocalActionStatus.EXECUTING))
            writableDatabase.setTransactionSuccessful()
            return true
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun interruptContinuation(
        checkpoint: AgentCheckpoint,
        reason: String,
        interrupted: Boolean,
    ) {
        writableDatabase.beginTransaction()
        try {
            saveCheckpoint(checkpoint.copy(phase = ContinuationPhase.INTERRUPTED))
            pendingActions().filter { it.turnId == checkpoint.turnId && it.status in listOf(LocalActionStatus.PENDING, LocalActionStatus.APPROVED, LocalActionStatus.EXECUTING) }.forEach { action ->
                if (action.status == LocalActionStatus.EXECUTING)recordUnknownAction(action)
                savePendingAction(action.copy(status = if (action.status == LocalActionStatus.EXECUTING)LocalActionStatus.UNCERTAIN else LocalActionStatus.CANCELLED))
            }
            messages(checkpoint.channelId).find { it.id == checkpoint.turnId }?.let { message -> saveMessage(message.copy(state = if (interrupted)MessageState.INTERRUPTED else MessageState.FAILED, text = reason)) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun recoverContinuations() {
        writableDatabase.beginTransaction()
        try {
            checkpoints().filter { it.phase == ContinuationPhase.RUNNING }.forEach { checkpoint ->
                saveCheckpoint(checkpoint.copy(phase = ContinuationPhase.INTERRUPTED))
                pendingActions().filter { it.turnId == checkpoint.turnId && it.status in listOf(LocalActionStatus.PENDING, LocalActionStatus.APPROVED, LocalActionStatus.EXECUTING) }.forEach { action ->
                    if (action.status == LocalActionStatus.EXECUTING)recordUnknownAction(action)
                    savePendingAction(action.copy(status = if (action.status == LocalActionStatus.EXECUTING)LocalActionStatus.UNCERTAIN else LocalActionStatus.CANCELLED))
                }
                messages(checkpoint.channelId).find { it.id == checkpoint.turnId }?.let { message -> saveMessage(message.copy(state = MessageState.INTERRUPTED, text = "The turn was interrupted. Check its saved actions before continuing; an action with an unknown outcome is never repeated automatically.")) }
            }
            // An automatically allowed call may have started before a durable waiting checkpoint
            // ever existed. It still has to surface an unknown outcome after process death.
            pendingActions().filter { it.status == LocalActionStatus.EXECUTING || it.automatic && it.status == LocalActionStatus.APPROVED }.forEach { action ->
                if (action.status == LocalActionStatus.EXECUTING) {
                    recordUnknownAction(action)
                    savePendingAction(action.copy(status = LocalActionStatus.UNCERTAIN))
                } else {
                    savePendingAction(action.copy(status = LocalActionStatus.CANCELLED))
                    if (toolEvents(action.channelId).none { it.turnId == action.turnId && it.callId == action.call.id })saveToolEvent(LocalToolEvent(UUID.randomUUID().toString(), action.turnId, action.channelId, action.call.id, action.call.name, action.call.arguments, NOT_DONE, true, Instant.now().toString()))
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    private fun createConnections(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE connections(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
    }

    fun connections(): List<LocalConnection> = query("SELECT payload FROM connections ORDER BY rowid")

    fun saveConnection(connection: LocalConnection) {
        require(connection.name.isNotBlank() && connection.name.length <= 100) { "Connection name must be 1–100 characters." }
        mcpEndpoint(connection.url)
        require(
            connection.tools
                .map { it.name }
                .distinct()
                .size == connection.tools.size,
        ) { "The connection has duplicate tool names." }
        require(connection.grants.keys.all { bot -> bots().any { it.id == bot } }) { "A granted Bot no longer exists." }
        require(
            connection.grants.values
                .flatten()
                .all { name -> connection.tools.any { it.name == name } },
        ) { "A granted tool is no longer available." }
        val values = ContentValues().apply { put("payload", json.encodeToString(connection)) }
        if (writableDatabase.update("connections", values, "id=?", arrayOf(connection.id)) == 0) {
            values.put("id", connection.id)
            writableDatabase.insertOrThrow("connections", null, values)
        }
        databaseChanged()
    }

    fun deleteConnection(id: String) {
        writableDatabase.delete("connections", "id=?", arrayOf(id))
        databaseChanged()
    }

    private fun createToolEvents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tool_events(id TEXT PRIMARY KEY,channel_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,turn_id TEXT NOT NULL,call_id TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(turn_id,call_id))")
        db.execSQL("CREATE INDEX tool_events_channel ON tool_events(channel_id)")
    }

    fun toolEvents(channelId: String): List<LocalToolEvent> = query("SELECT payload FROM tool_events WHERE channel_id=? ORDER BY rowid", arrayOf(channelId))

    fun saveToolEvent(event: LocalToolEvent) {
        val old = toolEvents(event.channelId).firstOrNull { it.turnId == event.turnId && it.callId == event.callId }
        if (old != null) {
            require(old.name == event.name && old.arguments == event.arguments) { "A tool identity cannot change its action." }
            require(old.result == OUTCOME_UNKNOWN || old.result == event.result) { "A completed tool result cannot be replaced." }
            writableDatabase.update("tool_events", ContentValues().apply { put("payload", json.encodeToString(event.copy(id = old.id))) }, "id=?", arrayOf(old.id))
        } else {
            writableDatabase.insertOrThrow(
                "tool_events",
                null,
                ContentValues().apply {
                    put("id", event.id)
                    put("channel_id", event.channelId)
                    put("turn_id", event.turnId)
                    put("call_id", event.callId)
                    put("payload", json.encodeToString(event))
                },
            )
        }
        databaseChanged()
    }

    private fun recordUnknownAction(action: PendingLocalAction) {
        if (toolEvents(action.channelId).none { it.turnId == action.turnId && it.callId == action.call.id })saveToolEvent(LocalToolEvent(UUID.randomUUID().toString(), action.turnId, action.channelId, action.call.id, action.call.name, action.call.arguments, OUTCOME_UNKNOWN, true, Instant.now().toString(), "${action.connectionName ?: "Connected app"} · ${action.tool.name.replace('_',' ')}"))
    }

    fun resolveUnknownAction(
        id: String,
        completed: Boolean,
    ) {
        writableDatabase.beginTransaction()
        try {
            val action = pendingActions().firstOrNull { it.id == id && it.status == LocalActionStatus.UNCERTAIN } ?: error("This action is no longer waiting for a result check.")
            savePendingAction(action.copy(status = if (completed)LocalActionStatus.EXECUTED else LocalActionStatus.CANCELLED))
            recordUnknownAction(action)
            val result = if (completed)"The person checked and confirmed that this action completed." else "The person checked and confirmed that this action did not happen. Ask again before retrying."
            saveToolEvent(LocalToolEvent(UUID.randomUUID().toString(), action.turnId, action.channelId, action.call.id, action.call.name, action.call.arguments, result, !completed, Instant.now().toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    private fun createMemoryTombstones(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE forgotten_memories(digest TEXT PRIMARY KEY)")
    }

    private fun memoryDigest(content: String) =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(content.lowercase(java.util.Locale.ROOT).toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun recallMemory(
        botId: String,
        search: String = "",
    ): List<Memory> {
        require(search.length <= 200) { "Search must be 200 characters or fewer." }
        return memories().filter { it.enabled && (it.formedBy != FormedBy.BOT || it.formedByAgentId == botId) && it.content.contains(search, ignoreCase = true) }
    }

    fun saveObservedMemory(
        content: String,
        botId: String,
        sourceApp: String,
        sourceLink: String?,
        observedAt: String?,
    ): String {
        require(content.trim().isNotEmpty() && content.trim().length <= 2000) { "An observed fact must be 1–2000 characters." }
        require(sourceApp.trim().isNotEmpty() && sourceApp.trim().length <= 160) { "Provide a source of 1–160 characters." }
        require(sourceLink == null || sourceLink.length <= 1000 && webLink(sourceLink) != null) { "Links must be http or https URLs." }
        val digest = memoryDigest(content.trim())
        val forgotten = readableDatabase.rawQuery("SELECT digest FROM forgotten_memories WHERE digest=?", arrayOf(digest)).use { it.moveToFirst() }
        if (forgotten)return "{\"duplicate\":true,\"forgotten\":true}"
        memories().firstOrNull { it.formedBy == FormedBy.BOT && it.formedByAgentId == botId && memoryDigest(it.content) == digest }?.let { return "{\"id\":\"${it.id}\",\"duplicate\":true,\"forgotten\":false}" }
        val observed =
            observedAt?.let {
                java.time.OffsetDateTime
                    .parse(it)
                    .toInstant()
                    .toString()
            } ?: Instant.now().toString()
        val provenance = (sourceApp.trim() + (sourceLink?.let { " ($it)" } ?: "") + ", observed $observed").take(500)
        val memory = Memory(UUID.randomUUID().toString(), content.trim(), provenance, true, ReviewState.UNREVIEWED, FormedBy.BOT, Instant.now().toString(), botId, sourceApp.trim(), sourceLink, observed)
        saveMemory(memory)
        return json.encodeToString(memory)
    }

    private fun createRoutineTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE routines (id TEXT PRIMARY KEY, bot_id TEXT NOT NULL REFERENCES bots(id), payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE routine_runs (id TEXT PRIMARY KEY, routine_id TEXT NOT NULL REFERENCES routines(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX routine_runs_routine ON routine_runs(routine_id)")
    }

    fun routines(): List<LocalRoutine> = query("SELECT payload FROM routines ORDER BY rowid")

    fun routineRuns(id: String): List<LocalRoutineRun> = query<LocalRoutineRun>("SELECT payload FROM routine_runs WHERE routine_id=? ORDER BY rowid DESC", arrayOf(id))

    fun routineRun(id: String): LocalRoutineRun? = query<LocalRoutineRun>("SELECT payload FROM routine_runs WHERE id=?", arrayOf(id)).firstOrNull()

    fun resumeRoutineRun(id: String) {
        val run = routineRun(id) ?: return
        if (run.status != RoutineStatus.WAITING)return
        writableDatabase.update("routine_runs", ContentValues().apply { put("payload", json.encodeToString(run.copy(status = RoutineStatus.RUNNING, finishedAt = null))) }, "id=?", arrayOf(id))
        databaseChanged()
    }

    fun saveRoutine(
        input: LocalRoutine,
        now: Instant = Instant.now(),
    ): LocalRoutine {
        writableDatabase.beginTransaction()
        try {
            val old = routines().find { it.id == input.id }
            require(conversations().any { it.id == input.channelId && it.botId == input.botId }) { "I can only post into a conversation you and I are both in." }
            require(!input.enabled || old?.enabled == true || routines().count { it.enabled } < 20) { "You already have 20 routines switched on. Switch one off before adding another." }
            input.goalId?.let { id ->
                val goal = GoalStore(this).get(id)
                require(goal.botId == input.botId && goal.status != GoalStatus.COMPLETED) { "Choose a goal belonging to this Bot that is not complete." }
            }
            val valid = validateRoutine(input, now)
            val saved = if (old != null && old.cron == input.cron && old.timezone == input.timezone && !(input.enabled && !old.enabled))valid.copy(nextRunAt = old.nextRunAt) else valid
            putRoutine(saved)
            writableDatabase.setTransactionSuccessful()
            databaseChanged()
            return saved
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    private fun putRoutine(routine: LocalRoutine) {
        val values = ContentValues().apply { put("payload", json.encodeToString(routine)) }
        if (writableDatabase.update("routines", values, "id=?", arrayOf(routine.id)) == 0) {
            values.put("id", routine.id)
            values.put("bot_id", routine.botId)
            writableDatabase.insertOrThrow("routines", null, values)
        }
    }

    fun deleteRoutine(id: String) {
        writableDatabase.delete("routines", "id=?", arrayOf(id))
        databaseChanged()
    }

    /** The due stamp and open run are updated in one SQLite transaction, including across workers. */
    fun startRoutineRun(
        id: String,
        manual: Boolean,
        now: Instant = Instant.now(),
    ): LocalRoutineRun? {
        writableDatabase.beginTransaction()
        try {
            val routine = routines().find { it.id == id } ?: error("That routine does not exist.")
            require(routine.enabled) { "This routine is paused, and paused routines never run. Switch it on first." }
            if (!manual && Instant.parse(routine.nextRunAt) > now)return null
            val open = routineRuns(id).any { it.status == RoutineStatus.RUNNING }
            if (open) {
                if (manual)error("This routine is already running. Wait for that run to finish.")
                return null
            }
            val run = LocalRoutineRun(UUID.randomUUID().toString(), id, now.toString(), if (manual)"run_now" else "schedule")
            putRoutine(routine.copy(lastRunAt = now.toString(), nextRunAt = if (manual)routine.nextRunAt else nextOccurrence(routine.cron, routine.timezone, now).toString()))
            writableDatabase.insertOrThrow(
                "routine_runs",
                null,
                ContentValues().apply {
                    put("id", run.id)
                    put("routine_id", id)
                    put("payload", json.encodeToString(run))
                },
            )
            writableDatabase.setTransactionSuccessful()
            databaseChanged()
            return run
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun disableUnschedulable(
        routine: LocalRoutine,
        reason: String,
    ) {
        writableDatabase.beginTransaction()
        try {
            putRoutine(routine.copy(enabled = false))
            val now = Instant.now().toString()
            val run = LocalRoutineRun(UUID.randomUUID().toString(), routine.id, now, status = RoutineStatus.SKIPPED, finishedAt = now, error = reason.take(400))
            writableDatabase.insertOrThrow(
                "routine_runs",
                null,
                ContentValues().apply {
                    put("id", run.id)
                    put("routine_id", routine.id)
                    put("payload", json.encodeToString(run))
                },
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun reapRoutineRuns(now: Instant = Instant.now()) {
        routines().forEach { routine ->
            routineRuns(routine.id)
                .filter {
                    it.status == RoutineStatus.RUNNING && java.time.Duration
                        .between(Instant.parse(it.startedAt), now)
                        .toMinutes() >= 30
                }.forEach {
                    finishRoutineRun(it, RoutineStatus.SKIPPED, "The previous run was interrupted before it finished.", now)
                }
        }
    }

    fun finishRoutineRun(
        run: LocalRoutineRun,
        status: RoutineStatus,
        error: String? = null,
        now: Instant = Instant.now(),
    ) {
        val current = routineRuns(run.routineId).find { it.id == run.id } ?: return
        if (current.status != RoutineStatus.RUNNING)return
        val reason = error?.let { String(it.codePoints().limit(400).toArray(), 0, it.codePointCount(0, it.length).coerceAtMost(400)) }
        writableDatabase.update("routine_runs", ContentValues().apply { put("payload", json.encodeToString(current.copy(status = status, finishedAt = now.toString(), error = reason))) }, "id=?", arrayOf(run.id))
        databaseChanged()
        if (status == RoutineStatus.FAILED && routineRuns(run.routineId).take(10).let { it.size == 10 && it.all { r -> r.status == RoutineStatus.FAILED } }) {
            routines().find { it.id == run.routineId }?.let { putRoutine(it.copy(enabled = false)) }
        }
    }

    private fun createCapabilityTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE skills (slug TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE preferences (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
    }

    fun skills(): List<LocalSkill> = query("SELECT payload FROM skills ORDER BY rowid")

    fun preferences(): LocalPreferences = query<LocalPreferences>("SELECT payload FROM preferences WHERE id=1").firstOrNull() ?: LocalPreferences()

    fun savePreferences(preferences: LocalPreferences) {
        require(preferences.name.length <= 100) { "Name must be 100 characters or fewer." }
        require(preferences.messageListEmphasis in listOf("agent", "thread")) { "Choose Bot names or conversation titles." }
        writableDatabase.insertWithOnConflict(
            "preferences",
            null,
            ContentValues().apply {
                put("id", 1)
                put("payload", json.encodeToString(preferences))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        databaseChanged()
    }

    fun saveSkill(
        skill: LocalSkill,
        creating: Boolean,
    ) {
        validateSkill(skill)
        val values =
            ContentValues().apply {
                put("slug", skill.slug)
                put("payload", json.encodeToString(skill))
            }
        if (creating) {
            check(skills().none { it.slug == skill.slug }) { "A skill already uses /${skill.slug}. Choose another command." }
            writableDatabase.insertOrThrow("skills", null, values)
        } else {
            check(writableDatabase.update("skills", values, "slug=?", arrayOf(skill.slug)) == 1) { "This skill no longer exists." }
        }
        databaseChanged()
    }

    fun deleteSkill(slug: String) {
        writableDatabase.delete("skills", "slug=?", arrayOf(slug))
        databaseChanged()
    }

    fun bots(): List<LocalBot> = query("SELECT payload FROM bots ORDER BY rowid")

    fun conversations(): List<LocalConversation> =
        query<LocalConversation>("SELECT payload FROM conversations ORDER BY created_at DESC, rowid DESC")
            .sortedByDescending { it.lastMessageAt ?: it.createdAt }

    fun messages(conversationId: String): List<LocalMessage> = query("SELECT payload FROM messages WHERE conversation_id=? ORDER BY created_at, rowid", arrayOf(conversationId))

    fun memories(): List<Memory> = query("SELECT payload FROM memories ORDER BY updated_at DESC, rowid DESC")

    fun saveBot(bot: LocalBot) {
        require(bot.name.isNotBlank() && bot.name.length <= 80) { "Name must be 1–80 characters." }
        require(bot.instruction.isNotBlank() && (bot.instruction.length <= 1000 || bots().any { it.id == bot.id && it.instruction == bot.instruction })) { "Instructions must be 1–1000 characters." }
        // UPDATE rather than REPLACE: preserve foreign-key relationships and the Bot's identity.
        val values = ContentValues().apply { put("payload", json.encodeToString(bot)) }
        if (writableDatabase.update("bots", values, "id=?", arrayOf(bot.id)) == 0) {
            values.put("id", bot.id)
            writableDatabase.insertOrThrow("bots", null, values)
        }
        databaseChanged()
    }

    fun deleteBot(id: String) {
        val files = AttachmentStore(appContext, this)
        val attachmentIds = conversations().filter { it.botId == id }.flatMap { files.conversation(it.id) }.map { it.id }
        val nested = writableDatabase.inTransaction()
        var deleted = false
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("routines", "bot_id=?", arrayOf(id))
            writableDatabase.delete("conversations", "bot_id=?", arrayOf(id))
            writableDatabase.delete("bots", "id=?", arrayOf(id))
            connections().filter { it.grants.containsKey(id) }.forEach { saveConnection(it.copy(grants = it.grants - id)) }
            writableDatabase.setTransactionSuccessful()
            deleted = true
            databaseChanged()
        } finally {
            writableDatabase.endTransaction()
            if (deleted && !nested)files.deleteUnreferenced(attachmentIds)
            databaseChanged()
        }
    }

    fun newConversation(botId: String): LocalConversation {
        val bot = bots().first { it.id == botId }
        val conversation = LocalConversation(UUID.randomUUID().toString(), botId, bot.name, Instant.now().toString())
        writableDatabase.insertOrThrow(
            "conversations",
            null,
            ContentValues().apply {
                put("id", conversation.id)
                put("bot_id", botId)
                put("created_at", conversation.createdAt)
                put("payload", json.encodeToString(conversation))
            },
        )
        databaseChanged()
        return conversation
    }

    fun saveMessage(message: LocalMessage) {
        writableDatabase.beginTransaction()
        try {
            val values =
                ContentValues().apply {
                    put("id", message.id)
                    put("conversation_id", message.conversationId)
                    put("created_at", message.createdAt)
                    put("payload", json.encodeToString(message))
                }
            if (writableDatabase.update("messages", values, "id=?", arrayOf(message.id)) == 0) {
                writableDatabase.insertOrThrow("messages", null, values)
            }
            if (message.state == MessageState.COMPLETE) {
                val conversation: LocalConversation = query<LocalConversation>("SELECT payload FROM conversations WHERE id=?", arrayOf(message.conversationId)).single()
                saveConversation(
                    conversation.copy(
                        opening = conversation.opening ?: message.text.takeIf { message.role == "user" },
                        lastMessage = message.text,
                        lastMessageAgentId = conversation.botId.takeIf { message.role != "user" },
                        lastMessageAt = message.createdAt,
                    ),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    /** A chosen title is separate from the generated summary; an empty title restores it. */
    fun renameConversation(
        id: String,
        title: String,
    ) {
        val chosen = title.trim().takeIf { it.isNotEmpty() }
        if (chosen != null) {
            val boundaries =
                android.icu.text.BreakIterator
                    .getCharacterInstance(java.util.Locale.ROOT)
            boundaries.setText(chosen)
            var count = 0
            while (boundaries.next() != android.icu.text.BreakIterator.DONE) count++
            require(count <= 60) { "Use a conversation name of at most 60 characters." }
        }
        writableDatabase.beginTransaction()
        try {
            val current = conversations().firstOrNull { it.id == id } ?: error("Conversation no longer exists.")
            saveConversation(current.copy(title = chosen))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun saveConversation(conversation: LocalConversation) {
        val values = ContentValues().apply { put("payload", json.encodeToString(conversation)) }
        check(writableDatabase.update("conversations", values, "id=?", arrayOf(conversation.id)) == 1) { "Conversation no longer exists." }
        databaseChanged()
    }

    fun deleteConversation(id: String) {
        val files = AttachmentStore(appContext, this)
        val ids = files.conversation(id).map { it.id }
        val nested = writableDatabase.inTransaction()
        writableDatabase.delete("conversations", "id=?", arrayOf(id))
        if (!nested)files.deleteUnreferenced(ids)
        databaseChanged()
    }

    fun beginTurn(
        user: LocalMessage,
        assistant: LocalMessage,
    ) {
        require(user.conversationId == assistant.conversationId) { "A turn must belong to one conversation." }
        writableDatabase.beginTransaction()
        try {
            saveMessage(user)
            saveMessage(assistant)
            writableDatabase.setTransactionSuccessful()
            databaseChanged()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun saveMemory(memory: Memory) {
        require(memory.content.isNotBlank() && memory.content.length <= 6000)
        val values =
            ContentValues().apply {
                put("id", memory.id)
                put("updated_at", memory.updatedAt)
                put("payload", json.encodeToString(memory))
            }
        if (writableDatabase.update("memories", values, "id=?", arrayOf(memory.id)) == 0) {
            writableDatabase.insertOrThrow("memories", null, values)
        }
        databaseChanged()
    }

    fun deleteMemory(id: String) {
        writableDatabase.beginTransaction()
        try {
            memories().find { it.id == id }?.let { memory -> writableDatabase.insertWithOnConflict("forgotten_memories", null, ContentValues().apply { put("digest", memoryDigest(memory.content)) }, SQLiteDatabase.CONFLICT_IGNORE) }
            writableDatabase.delete("memories", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
            databaseChanged()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    fun recoverInterruptedMessages() {
        writableDatabase.beginTransaction()
        try {
            val interrupted: List<LocalMessage> = query("SELECT payload FROM messages")
            interrupted.filter { it.state == MessageState.GENERATING }.forEach {
                val partial = it.text.takeIf { text -> text.isNotBlank() && text != "Working…" }
                saveMessage(it.copy(text = listOfNotNull(partial, "Generation was interrupted. Send your message again to retry.").joinToString("\n\n"), state = MessageState.INTERRUPTED))
            }
            writableDatabase.setTransactionSuccessful()
            databaseChanged()
        } finally {
            writableDatabase.endTransaction()
            databaseChanged()
        }
    }

    private inline fun <reified T> query(
        sql: String,
        args: Array<String>? = null,
    ): List<T> =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(json.decodeFromString<T>(cursor.getString(0))) }
        }
}

package ai.openbot.android

import ai.openbot.android.background.GoalWorker
import ai.openbot.android.data.ApprovalRuleStore
import ai.openbot.android.data.AttachmentStore
import ai.openbot.android.data.ConnectionSecrets
import ai.openbot.android.data.GoalStore
import ai.openbot.android.data.LocalDatabaseChanges
import ai.openbot.android.data.LocalStore
import ai.openbot.android.data.ModelSettingsStore
import ai.openbot.android.data.QuestionStore
import ai.openbot.android.data.TranscriptionSettingsStore
import ai.openbot.android.inference.AgentRuntime
import ai.openbot.android.inference.LocalModel
import ai.openbot.android.inference.McpClient
import ai.openbot.android.inference.ProviderModel
import ai.openbot.core.*
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

data class AppState(
    val bots: List<LocalBot> = emptyList(),
    val conversations: List<LocalConversation> = emptyList(),
    val draftAttachments: List<LocalAttachment> = emptyList(),
    val attachmentsLoading: Boolean = true,
    val conversationAttachments: List<LocalAttachment> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val selectedConversation: String? = null,
    val messages: List<LocalMessage> = emptyList(),
    val busy: Boolean = true,
    val error: String? = null,
    val modelInstalled: Boolean = false,
    val modelHash: String? = null,
    val providerSettings: ProviderSettings = ProviderSettings(),
    val providerKeyConfigured: Boolean = false,
    val dictationConfigured: Boolean = false,
    val dictationKeyConfigured: Boolean = false,
    val transcriptionSettings: ProviderSettings = ProviderSettings(model = "gpt-4o-mini-transcribe"),
    val homeDraft: String = "",
    val skills: List<LocalSkill> = emptyList(),
    val preferences: LocalPreferences = LocalPreferences(),
    val draftBotId: String? = null,
    val draftSessionId: String? = null,
    val routines: List<LocalRoutine> = emptyList(),
    val routineRuns: List<LocalRoutineRun> = emptyList(),
    val toolEvents: List<LocalToolEvent> = emptyList(),
    val generatingChannelId: String? = null,
    val stopping: Boolean = false,
    val connections: List<LocalConnection> = emptyList(),
    val pendingActions: List<PendingLocalAction> = emptyList(),
    val questions: List<LocalQuestion> = emptyList(),
    val continuingTurns: Set<String> = emptySet(),
    val goals: List<LocalGoal> = emptyList(),
    val goalHistoryFor: String? = null,
    val goalRuns: List<LocalGoalRun> = emptyList(),
    val approvalPreferences: ApprovalPreferences = ApprovalPreferences(false, false),
    val approvalRules: List<ApprovalRule> = emptyList(),
)

fun attachmentStageKey(state: AppState): String = state.selectedConversation?.let { "conversation:$it" } ?: state.draftSessionId?.let { "draft:$it" } ?: "home"

class OpenBotViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val attachmentStore = AttachmentStore(application, store)
    private val goalStore = GoalStore(store)
    private val ruleStore = ApprovalRuleStore(store)
    private val model = LocalModel(application)
    private val runtime = AgentRuntime(application, store, model)
    private val settingsStore = ModelSettingsStore(application)
    private val transcriptionStore = TranscriptionSettingsStore(application)
    private val connectionSecrets = ConnectionSecrets(application)
    private val state = MutableStateFlow(AppState())
    val ui = state.asStateFlow()
    private var initialized = false
    private var activeJob: Job? = null

    init {
        action {
            ai.openbot.android.inference.AgentGate.mutex
                .lock()
            try {
                store.recoverInterruptedMessages()
                store.recoverContinuations()
                store.reapRoutineRuns()
                attachmentStore.reap()
                refresh()
                store.checkpoints().filter { it.phase == ContinuationPhase.QUEUED }.forEach {
                    ai.openbot.android.background.ContinuationWorker
                        .enqueue(getApplication(), it.turnId)
                }
            } finally {
                ai.openbot.android.inference.AgentGate.mutex
                    .unlock()
            }
        }
        viewModelScope.launch {
            LocalDatabaseChanges.revision.collect { withContext(Dispatchers.IO) { refresh() } }
        }
    }

    @Synchronized private fun refresh() {
        val selected = state.value.selectedConversation
        val bots = store.bots()
        val conversations = store.conversations()
        val memories = store.memories()
        val messages = selected?.let(store::messages).orEmpty()
        val events = selected?.let(store::toolEvents).orEmpty()
        val stageKey = attachmentStageKey(state.value)
        val draftAttachments = attachmentStore.staged(stageKey)
        val conversationAttachments = selected?.let(attachmentStore::conversation).orEmpty()
        val installed = model.installed
        val settings = settingsStore.settings()
        val keyConfigured = settingsStore.hasKey()
        val transcription = transcriptionStore.settings()
        val dictationConfigured = transcriptionStore.configured()
        val dictationKeyConfigured = transcriptionStore.hasKey()
        val skills = store.skills()
        val preferences = store.preferences()
        val routines = store.routines()
        val runs = routines.flatMap { store.routineRuns(it.id) }
        val connections = store.connections()
        val actions = store.pendingActions()
        val questions = QuestionStore(store).waiting()
        val continuingTurns =
            store
                .checkpoints()
                .filter { it.phase in listOf(ContinuationPhase.QUEUED, ContinuationPhase.RUNNING) }
                .map { it.turnId }
                .toSet()
        val approvalPreferences = ruleStore.preferences()
        val approvalRules = ruleStore.rules()
        val goals = goalStore.list()
        val historyId = state.value.goalHistoryFor
        val goalRuns = historyId?.let(goalStore::runs).orEmpty()
        // Read SQLite first, then atomically merge into the latest UI state. An observer must
        // never overwrite a busy gate or navigation change with a snapshot from before its read.
        state.update { current ->
            current.copy(
                bots = bots,
                conversations = conversations,
                memories = memories,
                messages = if (current.selectedConversation == selected)messages else current.messages,
                draftAttachments = if (attachmentStageKey(current) == stageKey)draftAttachments else current.draftAttachments,
                attachmentsLoading = if (attachmentStageKey(current) == stageKey)false else current.attachmentsLoading,
                conversationAttachments = if (current.selectedConversation == selected)conversationAttachments else current.conversationAttachments,
                modelInstalled = installed,
                providerSettings = settings,
                providerKeyConfigured = keyConfigured,
                transcriptionSettings = transcription,
                dictationConfigured = dictationConfigured,
                dictationKeyConfigured = dictationKeyConfigured,
                skills = skills,
                preferences = preferences,
                routines = routines,
                routineRuns = runs,
                connections = connections,
                pendingActions = actions,
                questions = questions,
                continuingTurns = continuingTurns,
                approvalPreferences = approvalPreferences,
                approvalRules = approvalRules,
                goals = goals,
                goalRuns = if (current.goalHistoryFor == historyId)goalRuns else current.goalRuns,
                toolEvents = if (current.selectedConversation == selected)events else current.toolEvents,
            )
        }
        runCatching {
            ai.openbot.android.notifications.LocalNotifications
                .sync(getApplication(), store)
        }
    }

    fun dismissError() {
        state.value = state.value.copy(error = null)
    }

    fun saveTranscriptionSettings(
        baseUrl: String,
        model: String,
        key: String?,
        clearKey: Boolean,
        onSaved: () -> Unit,
    ) = action {
        transcriptionStore.save(baseUrl, model, key, clearKey)
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun disableDictation(onSaved: () -> Unit) =
        action {
            transcriptionStore.clear()
            refresh()
            withContext(Dispatchers.Main) { onSaved() }
        }

    fun savePreferences(
        preferences: LocalPreferences,
        onSaved: () -> Unit = {},
    ) = action {
        store.savePreferences(preferences)
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun saveSkill(
        skill: LocalSkill,
        creating: Boolean,
        onSaved: () -> Unit,
    ) = action {
        store.saveSkill(skill, creating)
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun deleteSkill(slug: String) =
        action {
            store.deleteSkill(slug)
            refresh()
        }

    fun setHomeDraft(value: String) {
        state.value = state.value.copy(homeDraft = value)
    }

    fun openConversation(id: String?) {
        if (id == null) {
            state.value = state.value.copy(selectedConversation = null, draftBotId = null, draftSessionId = null, messages = emptyList(), draftAttachments = emptyList(), conversationAttachments = emptyList(), attachmentsLoading = true)
            viewModelScope.launch { withContext(Dispatchers.IO) { refresh() } }
            return
        }
        action {
            require(store.conversations().any { it.id == id }) { "Conversation no longer exists." }
            store.saveConversation(store.conversations().first { it.id == id }.copy(lastReadAt = now()))
            state.value = state.value.copy(selectedConversation = id, draftBotId = null, draftSessionId = null, messages = emptyList(), draftAttachments = emptyList(), conversationAttachments = emptyList())
            refresh()
        }
    }

    /** A background notification can arrive before the UI's database snapshot catches up. */
    fun openNotification(
        destination: NotificationDestination,
        onOpened: (Boolean) -> Unit,
    ): Boolean {
        if (state.value.busy && initialized)return false
        action {
            var found = false
            try {
                val id =
                    when (destination) {
                        is NotificationDestination.Conversation -> destination.channelId
                        is NotificationDestination.Approvals -> destination.channelId
                        else -> null
                    }
                val channel = store.conversations().firstOrNull { it.id == id }
                if (channel != null) {
                    if (destination is NotificationDestination.Conversation)store.saveConversation(channel.copy(lastReadAt = now()))
                    state.update {
                        it.copy(
                            selectedConversation = channel.id.takeIf { destination is NotificationDestination.Conversation },
                            draftBotId = null,
                            draftSessionId = null,
                            messages = emptyList(),
                            toolEvents = emptyList(),
                            draftAttachments = emptyList(),
                            conversationAttachments = emptyList(),
                            attachmentsLoading = true,
                        )
                    }
                    refresh()
                    found = true
                }
            } finally {
                withContext(Dispatchers.Main) { onOpened(found) }
            }
        }
        return true
    }

    fun newConversation(botId: String) {
        if (state.value.busy)return
        require(state.value.bots.any { it.id == botId }) { "This Bot no longer exists." }
        state.value = state.value.copy(draftBotId = botId, draftSessionId = UUID.randomUUID().toString(), selectedConversation = null, messages = emptyList(), draftAttachments = emptyList(), conversationAttachments = emptyList(), attachmentsLoading = false, error = null)
    }

    fun pinConversation(channel: LocalConversation) =
        action {
            store.saveConversation(channel.copy(pinned = !channel.pinned))
            refresh()
        }

    fun renameConversation(
        channel: LocalConversation,
        title: String,
        onSaved: () -> Unit,
    ) = action {
        store.renameConversation(channel.id, title)
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun deleteConversation(
        channel: LocalConversation,
        onDeleted: () -> Unit = {},
    ) = action {
        store.checkpoints().filter { it.channelId == channel.id }.forEach {
            androidx.work.WorkManager
                .getInstance(getApplication())
                .cancelUniqueWork("openbot-continuation:${it.turnId}")
        }
        store.deleteConversation(channel.id)
        if (state.value.selectedConversation == channel.id) state.value = state.value.copy(selectedConversation = null)
        refresh()
        withContext(Dispatchers.Main) { onDeleted() }
    }

    fun saveBot(
        id: String?,
        name: String,
        instruction: String,
        onSaved: () -> Unit = {},
    ) = action {
        val existing = store.bots().find { it.id == id }
        val role = instruction.trim().ifEmpty { "You are ${name.trim()}, an AI coworker. Help the person with the work they ask you to do." }
        store.saveBot(existing?.copy(name = name.trim(), instruction = role) ?: LocalBot(UUID.randomUUID().toString(), name.trim(), role))
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun setPaused(
        bot: LocalBot,
        paused: Boolean,
    ) = action {
        store.saveBot(bot.copy(paused = paused))
        refresh()
    }

    fun setBotPinned(bot: LocalBot) =
        action {
            store.saveBot(bot.copy(pinned = !bot.pinned))
            refresh()
        }

    fun setBotHidden(bot: LocalBot) =
        action {
            store.saveBot(bot.copy(hidden = !bot.hidden))
            refresh()
        }

    fun duplicateBot(bot: LocalBot) =
        action {
            store.saveBot(bot.copy(id = UUID.randomUUID().toString(), name = (bot.name + " copy").take(80), pinned = false, hidden = false))
            refresh()
        }

    fun deleteBot(
        bot: LocalBot,
        onDeleted: () -> Unit,
    ) = action {
        store.deleteBot(bot.id)
        if (state.value.conversations.any { it.botId == bot.id && it.id == state.value.selectedConversation })state.value = state.value.copy(selectedConversation = null)
        refresh()
        withContext(Dispatchers.Main) { onDeleted() }
    }

    fun saveMemory(
        id: String?,
        content: String,
        onSaved: () -> Unit = {},
    ) = action {
        val previous = store.memories().find { it.id == id }
        store.saveMemory(
            previous?.copy(content = content.trim(), reviewState = ReviewState.EDITED, updatedAt = now())
                ?: Memory(UUID.randomUUID().toString(), content.trim(), "Added on this device", true, ReviewState.CONFIRMED, FormedBy.PERSON, now()),
        )
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun setMemoryEnabled(
        memory: Memory,
        enabled: Boolean,
    ) = action {
        store.saveMemory(memory.copy(enabled = enabled, updatedAt = now()))
        refresh()
    }

    fun confirmMemory(memory: Memory) =
        action {
            store.saveMemory(memory.copy(reviewState = ReviewState.CONFIRMED, updatedAt = now()))
            refresh()
        }

    fun deleteMemory(id: String) =
        action {
            store.deleteMemory(id)
            refresh()
        }

    fun importModel(uri: Uri) =
        action {
            val hash = model.import(uri)
            settingsStore.save(settingsStore.settings().copy(mode = ModelMode.ON_DEVICE), null, false)
            state.value = state.value.copy(modelHash = hash)
            refresh()
        }

    fun saveProviderSettings(
        settings: ProviderSettings,
        key: String?,
        clearKey: Boolean,
        onSaved: () -> Unit,
    ) = action {
        if (settings.mode == ModelMode.PROVIDER_API) providerEndpoint(settings)
        settingsStore.save(settings, key, clearKey)
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun addAttachments(uris: List<Uri>) {
        val key = attachmentStageKey(state.value)
        action {
            require(attachmentStore.staged(key).size + uris.size <= MAX_ATTACHMENTS_PER_MESSAGE) { "You can attach $MAX_ATTACHMENTS_PER_MESSAGE files to a message." }
            val errors = mutableListOf<String>()
            for (uri in uris) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val name =
                        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst())cursor.getString(0) else null
                        } ?: "attachment"
                    val input = resolver.openInputStream(uri) ?: error("Could not read '$name'.")
                    attachmentStore.stage(key, name, resolver.getType(uri).orEmpty(), input)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    errors.add(failure.message ?: "Could not attach this file.")
                }
            }
            refresh()
            require(errors.isEmpty()) { errors.joinToString("\n") }
        }
    }

    fun removeAttachment(id: String) =
        action {
            attachmentStore.remove(id, attachmentStageKey(state.value))
            refresh()
        }

    fun attachmentFile(attachment: LocalAttachment): java.io.File? = attachmentStore.preview(attachment.id, attachmentStageKey(state.value), state.value.selectedConversation)

    fun openAttachment(attachment: LocalAttachment) =
        action {
            val context = getApplication<Application>()
            val file = attachmentFile(attachment) ?: error("This attachment is no longer available.")
            val uri =
                androidx.core.content.FileProvider
                    .getUriForFile(context, context.packageName + ".files", file, attachment.name)
            try {
                context.startActivity(
                    android.content
                        .Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, attachment.mimeType)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: android.content.ActivityNotFoundException) {
                error("No app can open this file.")
            }
        }

    fun send(
        text: String,
        onAccepted: () -> Unit,
    ) {
        val key = attachmentStageKey(state.value)
        val ids = state.value.draftAttachments.map { it.id }
        action {
            require(attachmentStageKey(state.value) == key) { "The conversation changed. Send from its composer." }
            require(text.isNotBlank() || ids.isNotEmpty()) { "Enter a message or attach a file first." }
            attachmentStore.validateSend(ids, key)
            if (state.value.selectedConversation == null && state.value.draftBotId != null) {
                checkRuntimeConfigured()
                val channel = store.newConversation(requireNotNull(state.value.draftBotId))
                state.value = state.value.copy(selectedConversation = channel.id, draftBotId = null, draftSessionId = null)
                refresh()
            }
            sendTurn(text, onAccepted, ids, key)
        }
    }

    private fun checkRuntimeConfigured() {
        if (settingsStore.settings().mode == ModelMode.ON_DEVICE) {
            check(model.installed) { "Import an on-device model in Model settings first." }
        } else {
            check(settingsStore.hasKey()) { "Add your model provider key in Model settings first." }
        }
    }

    fun startConversation(
        text: String,
        botId: String,
    ) = action {
        val ids = attachmentStore.staged("home").map { it.id }
        require(text.isNotBlank() || ids.isNotEmpty()) { "Enter a message or attach a file first." }
        attachmentStore.validateSend(ids, "home")
        val settings = settingsStore.settings()
        if (settings.mode == ModelMode.ON_DEVICE) {
            check(model.installed) { "Import an on-device model in Model settings first." }
        } else {
            check(settingsStore.hasKey()) { "Add your model provider key in Model settings first." }
        }
        val bot = store.bots().first { it.id == botId }
        check(!bot.paused) { "This Bot is paused. Resume it before sending a message." }
        val conversation = store.newConversation(botId)
        state.value = state.value.copy(selectedConversation = conversation.id)
        refresh()
        sendTurn(text, { setHomeDraft("") }, ids, "home")
    }

    private suspend fun sendTurn(
        text: String,
        onAccepted: () -> Unit,
        attachmentIds: List<String> = emptyList(),
        stageKey: String? = null,
    ) {
        require(text.isNotBlank() || attachmentIds.isNotEmpty()) { "Enter a message or attach a file first." }
        val channelId = state.value.selectedConversation ?: error("Open a conversation first.")
        state.update { it.copy(generatingChannelId = channelId, stopping = false) }
        try {
            runtime.turn(channelId, text, onChanged = { refresh() }, onAccepted = { withContext(Dispatchers.Main) { onAccepted() } }, attachmentIds = attachmentIds, attachmentStageKey = stageKey)
        } finally {
            state.update { it.copy(generatingChannelId = null, stopping = false) }
        }
    }

    fun stopContinuation(turnId: String) =
        action {
            val checkpoint = store.checkpoints().firstOrNull { it.turnId == turnId } ?: return@action
            store.interruptContinuation(checkpoint, "Stopped by you. Check the saved actions for any result that was already started.", true)
            androidx.work.WorkManager
                .getInstance(getApplication())
                .cancelUniqueWork("openbot-continuation:$turnId")
            refresh()
        }

    fun resolveUnknownAction(
        id: String,
        completed: Boolean,
    ) = action {
        store.resolveUnknownAction(id, completed)
        refresh()
    }

    fun answerQuestion(
        id: String,
        text: String,
        onAnswered: () -> Unit = {},
    ) = action {
        val checkpoint = QuestionStore(store).answer(id, text)
        ai.openbot.android.background.ContinuationWorker
            .enqueue(getApplication(), checkpoint.turnId)
        refresh()
        withContext(Dispatchers.Main) { onAnswered() }
    }

    fun decideApproval(
        id: String,
        decision: LocalActionStatus,
        always: Boolean = false,
    ) = action {
        val checkpoint = store.decideAction(id, decision, always)
        ai.openbot.android.background.ContinuationWorker
            .enqueue(getApplication(), checkpoint.turnId)
        refresh()
    }

    fun saveApprovalPreferences(preferences: ApprovalPreferences) =
        action {
            ruleStore.savePreferences(preferences)
            if (!preferences.enabled) {
                ruleStore.withdraw(WITHDRAWN_APPROVALS_OFF) { it.policy?.source in listOf(null, "default") }.forEach {
                    ai.openbot.android.background.ContinuationWorker
                        .enqueue(getApplication(), it)
                }
            }
            refresh()
        }

    fun saveApprovalRule(
        rule: ApprovalRule,
        onSaved: () -> Unit = {},
    ) = action {
        ruleStore.save(rule)
        if (rule.behaviour == RuleBehaviour.ALLOW) {
            ruleStore
                .withdraw(WITHDRAWN_RULE_ALLOWS) { action ->
                    (action.policy?.source in listOf(null, "default", "personal_rule")) && ruleMatches(rule, pendingCandidate(action))
                }.forEach {
                    ai.openbot.android.background.ContinuationWorker
                        .enqueue(getApplication(), it)
                }
        }
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun removeApprovalRule(id: String) =
        action {
            ruleStore.delete(id)
            refresh()
        }

    fun connectMcp(
        id: String?,
        name: String,
        url: String,
        newToken: String?,
        clearToken: Boolean,
        onSaved: () -> Unit,
    ) = action {
        require(name.trim().isNotEmpty() && name.trim().length <= 100) { "Connection name must be 1–100 characters." }
        val endpoint = mcpEndpoint(url).toASCIIString()
        val old = store.connections().find { it.id == id }
        val connectionId = old?.id ?: UUID.randomUUID().toString()
        val token = newToken?.takeIf { it.isNotBlank() }?.trim() ?: if (!clearToken && old?.url == endpoint)old.credentialRef?.let(connectionSecrets::token) else null
        val proposed = LocalConnection(connectionId, name.trim(), endpoint, updatedAt = now())
        val client = McpClient(proposed, token)
        try {
            val serverName = client.initialize()
            val tools = client.listTools()
            val names = tools.map { it.name }.toSet()
            val grants = if (old?.url == endpoint)old.grants.mapValues { (_, held) -> held.filter { it in names } } else emptyMap()
            // Write a new immutable credential slot, then switch SQLite's reference. A failed
            // metadata write must never attach a new endpoint's token to the old endpoint.
            val retained = old?.url == endpoint && !clearToken && newToken.isNullOrBlank() && token != null
            val credentialRef = if (retained)old?.credentialRef else token?.takeIf { it.isNotBlank() }?.let { UUID.randomUUID().toString() }
            try {
                if (!retained)credentialRef?.let { connectionSecrets.save(it, token) }
                store.saveConnection(proposed.copy(serverName = serverName, tools = tools, grants = grants, credentialRef = credentialRef))
            } catch (failure: Exception) {
                if (!retained)credentialRef?.let { runCatching { connectionSecrets.remove(it) } }
                throw failure
            }
            old?.credentialRef?.takeIf { it != credentialRef }?.let { runCatching { connectionSecrets.remove(it) } }
            refresh()
            withContext(Dispatchers.Main) { onSaved() }
        } finally {
            withContext(NonCancellable) { runCatching { client.close() } }
        }
    }

    fun grantConnectionTool(
        connection: LocalConnection,
        botId: String,
        toolName: String,
        enabled: Boolean,
    ) = action {
        val current = store.connections().firstOrNull { it.id == connection.id } ?: error("This connection no longer exists.")
        val granted = current.grants[botId].orEmpty().toMutableSet()
        if (enabled)granted.add(toolName) else granted.remove(toolName)
        store.saveConnection(current.copy(grants = current.grants + (botId to granted.toList())))
        refresh()
    }

    fun disconnectMcp(
        connection: LocalConnection,
        onDeleted: () -> Unit,
    ) = action {
        store.deleteConnection(connection.id)
        connection.credentialRef?.let { connectionSecrets.remove(it) }
        refresh()
        withContext(Dispatchers.Main) { onDeleted() }
    }

    fun stopGeneration() {
        if (state.value.generatingChannelId != null && !state.value.stopping) {
            state.update { it.copy(stopping = true) }
            activeJob?.cancel(CancellationException("Stopped by you."))
        }
    }

    fun loadGoalHistory(id: String?) {
        state.update { it.copy(goalHistoryFor = id, goalRuns = emptyList()) }
        viewModelScope.launch { withContext(Dispatchers.IO) { refresh() } }
    }

    fun saveGoal(
        id: String?,
        botId: String,
        channelId: String,
        title: String,
        instruction: String,
        criteria: String,
        onSaved: () -> Unit,
    ) = action {
        val now = now()
        val existing = id?.let(goalStore::get)
        val goal =
            existing?.copy(title = title, instruction = instruction, successCriteria = criteria, updatedAt = now)
                ?: LocalGoal(UUID.randomUUID().toString(), botId, channelId, title, instruction, criteria, createdAt = now, updatedAt = now)
        goalStore.save(goal)
        refresh()
        withContext(Dispatchers.Main) { onSaved() }
    }

    fun setGoalStatus(
        goal: LocalGoal,
        status: GoalStatus,
    ) = action {
        goalStore.transition(goal.id, status)
        if (status != GoalStatus.ACTIVE) {
            goalStore.runs(goal.id).filter { it.status in listOf(GoalRunStatus.QUEUED, GoalRunStatus.RUNNING) }.forEach { run ->
                if (run.status == GoalRunStatus.QUEUED)goalStore.finish(run.id, GoalRunStatus.SKIPPED, error = "The goal was paused or completed before it ran.")
                androidx.work.WorkManager
                    .getInstance(getApplication())
                    .cancelUniqueWork("openbot-goal-run:${run.id}")
            }
            if (status == GoalStatus.COMPLETED) {
                store.checkpoints().filter { checkpoint -> checkpoint.goalRunId?.let(goalStore::run)?.goalId == goal.id }.forEach { checkpoint ->
                    store.interruptContinuation(checkpoint, "The goal was completed. The pending action was not done.", true)
                    checkpoint.goalRunId?.let { goalStore.finish(it, GoalRunStatus.SKIPPED, error = "The goal was completed.") }
                    androidx.work.WorkManager
                        .getInstance(getApplication())
                        .cancelUniqueWork("openbot-continuation:${checkpoint.turnId}")
                }
            }
        }
        refresh()
    }

    fun runGoal(goal: LocalGoal) =
        action {
            runtime.checkConfigured()
            goalStore.enqueue(goal.id)
            state.update { it.copy(goalHistoryFor = goal.id) }
            GoalWorker.enqueueQueued(getApplication(), store)
            refresh()
        }

    fun setRoutineEnabled(
        routine: LocalRoutine,
        enabled: Boolean,
    ) = action {
        store.saveRoutine(routine.copy(enabled = enabled))
        refresh()
    }

    fun deleteRoutine(
        routine: LocalRoutine,
        onDeleted: () -> Unit,
    ) = action {
        store.deleteRoutine(routine.id)
        refresh()
        withContext(Dispatchers.Main) { onDeleted() }
    }

    fun runRoutineNow(routine: LocalRoutine) =
        action {
            runtime.checkConfigured()
            val run = requireNotNull(store.startRoutineRun(routine.id, manual = true))
            ai.openbot.android.background.RoutineWorker
                .enqueueManual(getApplication(), run)
            refresh()
        }

    /** Main-thread gate prevents double taps from scheduling two writes or model generations. */
    private fun action(work: suspend () -> Unit) {
        // The initial state is busy until the initialization coroutine is launched below.
        if (state.value.busy && initialized) return
        initialized = true
        state.value = state.value.copy(busy = true, error = null)
        activeJob =
            viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { work() }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    state.value = state.value.copy(error = failure.message ?: "Could not complete this action.")
                } finally {
                    state.value = state.value.copy(busy = false)
                }
            }
    }

    override fun onCleared() {
        // Wait for any native inference to finish before releasing native resources and SQLite.
        val finishing = activeJob
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            finishing?.join()
            model.close()
            store.close()
        }
    }

    private fun now() = Instant.now().toString()
}

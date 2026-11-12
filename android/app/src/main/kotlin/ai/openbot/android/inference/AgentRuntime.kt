package ai.openbot.android.inference

import android.content.Context
import ai.openbot.android.data.*
import ai.openbot.android.background.GoalWorker
import ai.openbot.core.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

/** One shared gate serializes foreground turns and background routines in this Android process. */
object AgentGate { val mutex=Mutex() }
class ConversationWaitingException:IllegalStateException("Answer the waiting question or resolve the pending approval in this conversation before sending another message.")

class AgentRuntime(private val context:Context,private val store:LocalStore,private val model:LocalModel,
    private val settingsStore:ModelConfiguration=ModelSettingsStore(context),provider:ProviderTransport?=null,
    private val sessions:(LocalConnection,String?)->McpSession={connection,token->McpClient(connection,token)},
    enqueueGoals:(()->Unit)?=null) {
    private val attachments=AttachmentStore(context,store)
    private val attachmentWire=AttachmentWireSource(context,store)
    private val transport=provider?:ProviderModel(attachmentWire)
    private val goals=GoalStore(store)
    private val goalTools=GoalTools(store)
    // Bound only while this runtime holds AgentGate, including a restored continuation.
    private var currentGoalRunId:String?=null
    private var currentRoutineRunId:String?=null
    private val scheduleGoals=enqueueGoals?:{GoalWorker.enqueueQueued(context.applicationContext,store)}
    private val engine=ProviderTurnEngine(context,store,settingsStore,transport,::executeRecorded,sessions)
    private val routineTools:List<AgentTool> by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/routine-tools.json")).bufferedReader().use{it.readText()}).jsonArray.map {
            val obj=it.jsonObject;AgentTool(obj["name"]!!.jsonPrimitive.content,obj["description"]!!.jsonPrimitive.content,obj["inputSchema"]!!.jsonObject)
        }
    }
    internal fun availableTools():List<AgentTool> = listOf(askPersonTool) + routineTools + memoryTools + goalTools.definitions + if(currentGoalRunId!=null)listOf(goalProgressTool) else emptyList()
    fun checkConfigured() {
        if(settingsStore.settings().mode==ModelMode.ON_DEVICE)check(model.installed){"Import an on-device model in Model settings first."}
        else check(settingsStore.hasKey()) {"Add your model provider key in Model settings first."}
    }
    suspend fun turn(channelId:String,text:String,onChanged:suspend()->Unit={},onAccepted:suspend()->Unit={},routineRunId:String?=null,goalRunId:String?=null,attachmentIds:List<String> = emptyList(),attachmentStageKey:String?=null):TurnOutcome {
        AgentGate.mutex.lock()
        try {
            currentGoalRunId=goalRunId;currentRoutineRunId=routineRunId
            checkConfigured()
            val settings=settingsStore.settings()
            val channel=store.conversations().firstOrNull{it.id==channelId}?:error("Conversation no longer exists.")
            val bot=store.bots().first{it.id==channel.botId}
            if(store.checkpoints().any{it.channelId==channelId&&it.phase in listOf(ContinuationPhase.WAITING,ContinuationPhase.QUEUED,ContinuationPhase.RUNNING)})throw ConversationWaitingException()
            goalRunId?.let{id->val run=goals.run(id)?:error("This goal run no longer exists.");require(goals.get(run.goalId).status==GoalStatus.ACTIVE&&run.status==GoalRunStatus.RUNNING){"The goal is no longer active."}}
            val goalContext=goalRunId?.let(goals::run)?.let{goals.get(it.goalId)}
            val inputText=goalContext?.let{frameGoal(it,requireNotNull(goalRunId))}?:text
            val currentFiles=if(attachmentIds.isEmpty())emptyList() else attachments.validateSend(attachmentIds,requireNotNull(attachmentStageKey))
            require(settings.mode!=ModelMode.ON_DEVICE||currentFiles.none{classifyAttachment(it.mimeType)==AttachmentKind.IMAGE}){"This on-device model supports text messages only. Choose a vision model provider to send images."}
            val user=LocalMessage(UUID.randomUUID().toString(),channelId,"user",inputText.trim(),Instant.now().toString(),
                systemLabel=goalContext?.let{"Goal · ${it.title}"},displayText=goalContext?.instruction,attachmentIds=attachmentIds)
            val instructions=listOfNotNull(bot.instruction,store.preferences().standingInstructions.takeIf{it.isNotBlank()},pickedSkillGuidance(inputText,store.skills())).joinToString("\n\n")
            val tools=if(settings.mode==ModelMode.PROVIDER_API)availableTools()+engine.connectedTools(bot.id) else emptyList()
            require(tools.sumOf{it.schema.toString().length+it.description.length}<=256_000){"The granted tool descriptions exceed this model's tool budget. Reduce the Bot's grants first."}
            val history=store.messages(channelId)
            val promptUser=if(settings.mode==ModelMode.ON_DEVICE&&currentFiles.isNotEmpty())user.copy(text=user.text+"\n\n"+currentFiles.joinToString("\n\n"){file->
                val bytes=requireNotNull(attachments.preview(file.id,attachmentStageKey,null)).readBytes()
                "Attached file \"${file.name}\":\n\n${extractAttachmentText(bytes)}"
            }) else user
            val promptContext=localPromptContext(bot.copy(instruction=instructions),store.memories(),history+promptUser,
                maximumCharacters=if(settings.mode==ModelMode.ON_DEVICE)8000 else 64000,toolNames=tools.map{it.name},toolEvents=store.toolEvents(channelId))
            val prompt=promptContext.text
            val prepared=if(settings.mode==ModelMode.PROVIDER_API)prepareTurnAttachments(settings,channelId,prompt,history,promptContext.messageIds,user,currentFiles,attachments) else TurnAttachments(null,emptyList())
            val pending=LocalMessage(UUID.randomUUID().toString(),channelId,"assistant","Working…",Instant.now().toString(),MessageState.GENERATING)
            val db=store.writableDatabase;db.beginTransaction()
            try {
                store.beginTurn(user,pending)
                if(goalRunId!=null)goals.attachTurn(goalRunId,pending.id)
                if(currentFiles.isNotEmpty())attachments.bind(attachmentIds,requireNotNull(attachmentStageKey),channelId,user.id)
                db.setTransactionSuccessful()
            }finally{db.endTransaction();LocalDatabaseChanges.changed()}
            attachmentWire.authorize(channelId,prepared.selected)
            onChanged();onAccepted()
            try {
                val answer=if(settings.mode==ModelMode.ON_DEVICE)model.generate(prompt) else {
                    val protocol=AgentProtocol(settings,prompt,tools,initialContent=prepared.content)
                    engine.run(settings,tools,protocol,pending,bot.id,onChanged,routineRunId=routineRunId,goalRunId=goalRunId)?:return TurnOutcome.WAITING
                }
                val completed=pending.copy(text=answer,createdAt=Instant.now().toString(),state=MessageState.COMPLETE)
                store.saveMessage(completed)
                store.deleteCheckpoint(pending.id)
                if(routineRunId!=null||goalRunId!=null)runCatching{ai.openbot.android.notifications.LocalNotifications.reply(context,store,completed,bot.id)}
                return TurnOutcome.COMPLETED
            }catch(failure:Exception) {
                val failed=failedMessage(pending,failure)
                store.checkpoints().firstOrNull{it.turnId==pending.id}?.let{store.interruptContinuation(it,if(failure is CancellationException)"Generation was interrupted." else "Could not continue this turn.",failure is CancellationException)}
                store.saveMessage(failed)
                throw failure
            }finally {onChanged();runCatching{ai.openbot.android.notifications.LocalNotifications.sync(context,store)}}
        }finally {attachmentWire.clear();currentGoalRunId=null;currentRoutineRunId=null;AgentGate.mutex.unlock()}
    }
    suspend fun resume(turnId:String,onChanged:suspend()->Unit={}):TurnOutcome {
        AgentGate.mutex.lock()
        try {
            val checkpoint=store.claimContinuation(turnId)?:return TurnOutcome.COMPLETED
            currentGoalRunId=checkpoint.goalRunId;currentRoutineRunId=checkpoint.routineRunId
            val pending=store.messages(checkpoint.channelId).firstOrNull{it.id==turnId}?:error("This conversation no longer exists.")
            try {
                checkConfigured()
                require(settingsStore.settings()==checkpoint.settings){"Model settings changed while this turn was waiting. Send a new message to continue."}
                checkpoint.goalRunId?.let{id->val run=goals.run(id)?:error("This goal run no longer exists.");require(goals.get(run.goalId).status==GoalStatus.ACTIVE){"Resume the goal before answering its waiting run."}}
                checkpoint.routineRunId?.let{id->
                    val run=store.routineRun(id)?:error("This routine was deleted before the turn continued.")
                    require(store.routines().any{it.id==run.routineId&&it.enabled}){"This routine is paused. Turn it on before continuing."}
                }
                require(store.bots().any{it.id==checkpoint.botId&&!it.paused}){"This Bot is paused. Resume it before continuing."}
                attachmentWire.authorize(checkpoint.channelId,checkpointAttachments(checkpoint.history,checkpoint.channelId,attachments))
                val protocol=AgentProtocol(checkpoint.settings,"",checkpoint.tools,checkpoint.history)
                store.saveMessage(pending.copy(state=MessageState.GENERATING,text="Working…"));onChanged()
                val answer=engine.run(checkpoint.settings,checkpoint.tools,protocol,pending,checkpoint.botId,onChanged,checkpoint,checkpoint.routineRunId,checkpoint.goalRunId)?:return TurnOutcome.WAITING
                val completed=pending.copy(text=answer,createdAt=Instant.now().toString(),state=MessageState.COMPLETE)
                store.saveMessage(completed)
                store.deleteCheckpoint(turnId)
                runCatching{ai.openbot.android.notifications.LocalNotifications.reply(context,store,completed,checkpoint.botId)}
                return TurnOutcome.COMPLETED
            }catch(failure:Exception) {
                val failed=failedMessage(pending,failure)
                store.interruptContinuation(checkpoint,"Could not continue this turn. ${failure.message.orEmpty()}",failure is CancellationException)
                store.saveMessage(failed)
                throw failure
            }finally{onChanged();runCatching{ai.openbot.android.notifications.LocalNotifications.sync(context,store)}}
        }finally{attachmentWire.clear();currentGoalRunId=null;currentRoutineRunId=null;AgentGate.mutex.unlock()}
    }

    private fun failedMessage(pending:LocalMessage,failure:Exception):LocalMessage {
        val latest=store.messages(pending.conversationId).firstOrNull{it.id==pending.id}
        val partial=latest?.takeIf{it.state==MessageState.GENERATING&&it.text!="Working…"}?.text?.takeIf{it.isNotBlank()}
        val notice=if(failure is CancellationException)"Generation was interrupted." else "Could not generate an answer. ${failure.message.orEmpty()}"
        return pending.copy(text=listOfNotNull(partial,notice).joinToString("\n\n"),state=if(failure is CancellationException)MessageState.INTERRUPTED else MessageState.FAILED)
    }

    /** Local tool effects and their successful result commit together, once per provider call. */
    internal fun executeRecorded(call:ToolCall,botId:String,channelId:String,turnId:String):String {
        store.toolEvents(channelId).find{it.turnId==turnId&&it.callId==call.id}?.let {
            check(it.name==call.name&&it.arguments==call.arguments){"The model reused a tool identity for a different action."}
            runCatching{scheduleGoals()}
            return it.result
        }
        val db=store.writableDatabase
        val timestamp=Instant.now().toString()
        try {
            val result:String
            db.beginTransaction()
            try {
                currentGoalRunId?.let{id->val run=goals.run(id)?:error("This goal run no longer exists.");require(goals.get(run.goalId).status==GoalStatus.ACTIVE){"The goal is no longer active. The action was not done."}}
                currentRoutineRunId?.let { id ->
                    val run=store.routineRun(id)?:error("This routine run no longer exists.")
                    require(store.routines().any{it.id==run.routineId&&it.enabled}){"This routine is paused. The action was not done."}
                }
                require(store.bots().any{it.id==botId&&!it.paused}){"This Bot is paused or no longer exists. The action was not done."}
                require(store.checkpoints().none{it.turnId==turnId&&it.phase==ContinuationPhase.INTERRUPTED}){"This turn was stopped."}
                result=execute(call,botId,channelId)
                store.saveToolEvent(LocalToolEvent(UUID.randomUUID().toString(),turnId,channelId,call.id,call.name,call.arguments,result,false,timestamp))
                db.setTransactionSuccessful()
            }finally{db.endTransaction();LocalDatabaseChanges.changed()}
            runCatching{scheduleGoals()}
            return result
        }catch(failure:CancellationException){throw failure}
        catch(failure:Exception) {
            val result=buildJsonObject{put("isError",true);put("message",failure.message?:"The action failed.")}.toString()
            store.saveToolEvent(LocalToolEvent(UUID.randomUUID().toString(),turnId,channelId,call.id,call.name,call.arguments,result,true,timestamp))
            return result
        }
    }

    /** Owner and Bot come from the run, never a model-supplied argument. */
    internal fun execute(call:ToolCall,botId:String,channelId:String):String {
        val offered=availableTools().find{it.name==call.name}?:error("This tool is not available: ${call.name}.")
        validateToolArguments(offered,call.arguments)
        val args=call.arguments
        fun string(name:String)=args[name]?.jsonPrimitive?.contentOrNull
        fun required(name:String)=string(name)?.takeIf{it.isNotBlank()}?:error("Provide $name.")
        fun result(routine:LocalRoutine)=Json.encodeToString(LocalRoutine.serializer(),routine)
        if(goalTools.definitions.any{it.name==call.name}||call.name=="report_goal_progress")return goalTools.execute(call,botId,channelId,currentGoalRunId,currentRoutineRunId)
        return when(call.name) {
            "recall_personal_memory"->Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Memory.serializer()),store.recallMemory(botId,string("search").orEmpty()))
            "remember_personal_fact"->{
                val provenance=string("provenance")?.trim()?:"You"
                require(provenance.isNotBlank()&&provenance.length<=500){"Provide a source of 1–500 characters."}
                val memory=Memory(UUID.randomUUID().toString(),required("content").trim(),provenance,args["enabled"]?.jsonPrimitive?.booleanOrNull?:true,ReviewState.CONFIRMED,FormedBy.PERSON,Instant.now().toString())
                store.saveMemory(memory);Json.encodeToString(Memory.serializer(),memory)
            }
            "save_observed_memory"->store.saveObservedMemory(required("content"),botId,required("sourceApp"),string("sourceLink"),string("observedAt"))
            "forget_personal_memory"->{val id=required("id");require(store.memories().any{it.id==id&&(it.formedBy!=FormedBy.BOT||it.formedByAgentId==botId)}){"This memory could not be found."};store.deleteMemory(id);"{\"forgotten\":true}"}
            "list_routines"->Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(LocalRoutine.serializer()),store.routines())
            "create_routine"->{
                val now=Instant.now().toString()
                result(store.saveRoutine(LocalRoutine(UUID.randomUUID().toString(),botId,string("channelId")?:channelId,required("instruction"),string("title"),required("cron"),string("timezone")?:ZoneId.systemDefault().id,nextRunAt=now,createdAt=now,goalId=string("goalId")?.trim()?.takeIf{it.isNotEmpty()})))
            }
            "update_routine"->{
                val old=store.routines().firstOrNull{it.id==required("id")}?:error("That routine does not exist.")
                require(args.keys.any{it in setOf("title","instruction","cron","timezone","channelId","enabled","goalId")}) {"Say what to change about that routine."}
                result(store.saveRoutine(old.copy(title=string("title")?:old.title,instruction=string("instruction")?:old.instruction,cron=string("cron")?:old.cron,timezone=string("timezone")?:old.timezone,channelId=string("channelId")?:old.channelId,enabled=args["enabled"]?.jsonPrimitive?.booleanOrNull?:old.enabled,goalId=if(args.containsKey("goalId"))string("goalId")?.trim()?.takeIf{it.isNotEmpty()} else old.goalId)))
            }
            "delete_routine"->{val id=required("id");require(store.routines().any{it.id==id}){"That routine does not exist."};store.deleteRoutine(id);"That routine is deleted. It will not run again."}
            else->error("This tool is not available: ${call.name}.")
        }
    }
}

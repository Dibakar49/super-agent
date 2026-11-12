package ai.openbot.android.inference

import android.content.Context
import ai.openbot.android.data.*
import ai.openbot.core.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

enum class TurnOutcome { COMPLETED, WAITING }

/** Provider continuations pause durably before an external action, without keeping a network job open. */
internal class ProviderTurnEngine(
    context:Context,private val store:LocalStore,private val configuration:ModelConfiguration,
    private val provider:ProviderTransport,private val builtin:(ToolCall,String,String,String)->String,
    private val sessions:(LocalConnection,String?)->McpSession={connection,token->McpClient(connection,token)}
) {
    private val secrets=ConnectionSecrets(context)
    private val policy=ActionPolicy(store,configuration,provider)
    fun connectedTools(botId:String):List<AgentTool> = store.connections().flatMap{connection->
        connection.tools.filter{it.name in connection.grants[botId].orEmpty()}.map{tool->
            AgentTool(mcpModelToolName(connection.id,tool.name),"${connection.name}: ${tool.name}. ${tool.description}",tool.inputSchema)
        }
    }
    suspend fun run(settings:ProviderSettings,tools:List<AgentTool>,protocol:AgentProtocol,pending:LocalMessage,
        botId:String,onChanged:suspend()->Unit,checkpoint:AgentCheckpoint?=null,routineRunId:String?=null,goalRunId:String?=null):String? {
        var current=checkpoint?.round
        var outputs=checkpoint?.results?.toMutableList()?:mutableListOf()
        var rounds=checkpoint?.roundCount?:0
        var answeredAction=checkpoint?.approvalId?.let{id->store.pendingActions().firstOrNull{it.id==id}}
        // Every step after the one that paused must receive a result without running.
        // The next model round can plan again using the person's decision.
        var waitedIndex=checkpoint?.results?.size
        while(true) {
            if(current==null) {
                check(rounds<16){"This turn reached its tool-call limit. Saved actions were kept; ask the Bot to continue."}
                val (active,keyValue)=configuration.snapshot()
                check(active==settings){"Model settings changed during this turn. Saved actions were kept; send a new message to continue."}
                val key=keyValue?.takeIf{it.isNotBlank()}?:error("Add your model provider key in Model settings first.")
                current=protocol.read(provider.stream(settings,key,protocol.request()) { partial ->
                    currentCoroutineContext().ensureActive()
                    val latest=store.messages(pending.conversationId).firstOrNull{it.id==pending.id}
                    if(latest?.state==MessageState.GENERATING) {
                        store.saveMessage(latest.copy(text=partial))
                        onChanged()
                    }
                });rounds++
                outputs=mutableListOf()
            }
            val round=requireNotNull(current)
            if(round.calls.isEmpty())return round.text
            for(index in outputs.size until round.calls.size) {
                currentCoroutineContext().ensureActive()
                require(store.checkpoints().none{it.turnId==pending.id&&it.phase==ContinuationPhase.INTERRUPTED}){"This turn was stopped."}
                val call=round.calls[index]
                val previous=store.toolEvents(pending.conversationId).find{it.turnId==pending.id&&it.callId==call.id}
                val result=if(previous!=null) {
                    check(previous.name==call.name&&previous.arguments==call.arguments){"The model reused a tool identity for a different action."}
                    previous.result
                }else if(waitedIndex?.let{index>it}==true) {
                    record(call,pending,null,NOT_RUN_WHILE_WAITING,true)
                }else if(call.name==askPersonTool.name) {
                    val parsed=runCatching{parseQuestion(call)}
                    if(parsed.isFailure)record(call,pending,null,refusal(parsed.exceptionOrNull()?.message?:"Say what you need a person to answer."),true)
                    else {
                        val (question,why,parts)=parsed.getOrThrow()
                        val request=LocalQuestion(UUID.randomUUID().toString(),pending.id,pending.conversationId,botId,call,question,why,parts,Instant.now().toString())
                        QuestionStore(store).wait(AgentCheckpoint(pending.id,pending.conversationId,botId,settings,tools,protocol.snapshot(),round,
                            outputs.toList(),"",Instant.now().toString(),routineRunId=routineRunId,roundCount=rounds,goalRunId=goalRunId,questionId=request.id),request)
                        onChanged();currentCoroutineContext().ensureActive();return null
                    }
                }else if(!call.name.startsWith("mcp_"))builtin(call,botId,pending.conversationId,pending.id)
                else {
                    val decision=answeredAction?.takeIf{it.call.id==call.id}
                    val binding=binding(botId,call.name)
                    if(decision?.status==LocalActionStatus.DENIED) {
                        answeredAction=null;record(call,pending,decision,DECLINED_BY_PERSON,true,LocalActionStatus.DENIED)
                    } else if(decision?.status==LocalActionStatus.HANDLED) {
                        answeredAction=null;record(call,pending,decision,HANDLED_BY_PERSON,false,LocalActionStatus.HANDLED)
                    } else if(binding==null) {
                        answeredAction=null
                        record(call,pending,decision,notDoneOnRecheck("this connected tool is no longer granted to the Bot."),true,LocalActionStatus.CANCELLED)
                    }
                    else {
                        val(connection,tool)=binding
                        try {validateToolArguments(AgentTool(call.name,tool.description,tool.inputSchema),call.arguments)}
                        catch(failure:Exception){answeredAction=null;outputs.add(record(call,pending,decision,refusal(failure.message?:"Invalid tool arguments."),true,LocalActionStatus.CANCELLED));continue}
                        val action=decision
                        if(action==null) {
                            val uncertain=store.pendingActions().any{it.channelId==pending.conversationId&&it.connectionId==connection.id&&it.tool.name==tool.name&&it.call.arguments==call.arguments&&it.status==LocalActionStatus.UNCERTAIN}
                            if(uncertain){outputs.add(record(call,pending,null,OUTCOME_UNKNOWN,true));continue}
                            val outcome=policy.decide(connectedCandidate(botId,connection.id,connection.url,tool,call.arguments),pending.conversationId)
                            val request=PendingLocalAction(UUID.randomUUID().toString(),pending.id,pending.conversationId,botId,
                                connection.id,connection.url,connection.credentialRef,tool,call,Instant.now().toString(),
                                status=if(outcome.behaviour==PolicyBehaviour.ALLOW)LocalActionStatus.APPROVED else LocalActionStatus.PENDING,
                                automatic=outcome.behaviour==PolicyBehaviour.ALLOW,policy=outcome,connectionName=connection.name,
                                goalRunId=goalRunId,routineRunId=routineRunId)
                            if(outcome.behaviour==PolicyBehaviour.ALLOW) {
                                store.savePendingAction(request)
                                outputs.add(executeAction(request,connection,tool,call,pending));onChanged();continue
                            }
                            store.waitForApproval(AgentCheckpoint(pending.id,pending.conversationId,botId,settings,tools,protocol.snapshot(),round,
                                outputs.toList(),request.id,Instant.now().toString(),routineRunId=routineRunId,roundCount=rounds,goalRunId=goalRunId),request)
                            onChanged();currentCoroutineContext().ensureActive();return null
                        }
                        answeredAction=null
                        executeAction(action,connection,tool,call,pending)
                    }
                }
                outputs.add(result)
                store.checkpoints().find{it.turnId==pending.id&&it.phase==ContinuationPhase.RUNNING}?.let{saved->
                    store.saveCheckpoint(saved.copy(round=round,history=protocol.snapshot(),results=outputs.toList(),roundCount=rounds))
                }
                onChanged()
            }
            protocol.continueWith(round,outputs)
            current=null
            waitedIndex=null
        }
    }
    private fun binding(botId:String,modelName:String):Pair<LocalConnection,McpTool>? {
        for(connection in store.connections())for(tool in connection.tools) {
            if(tool.name in connection.grants[botId].orEmpty()&&mcpModelToolName(connection.id,tool.name)==modelName)return connection to tool
        }
        return null
    }
    private fun unchanged(action:PendingLocalAction,connection:LocalConnection,tool:McpTool):Boolean =
        action.connectionId==connection.id&&action.connectionUrl==connection.url&&action.credentialRef==connection.credentialRef&&action.tool==tool&&
            tool.name in connection.grants[action.botId].orEmpty()&&store.bots().any{it.id==action.botId&&!it.paused}&&store.pendingActions().any{it.id==action.id&&it.status==LocalActionStatus.APPROVED}

    private suspend fun executeAction(action:PendingLocalAction,connection:LocalConnection,tool:McpTool,call:ToolCall,pending:LocalMessage):String {
        require(action.call==call){"The tool action changed after approval."}
        if(action.status==LocalActionStatus.DENIED)return record(call,pending,action,DECLINED_BY_PERSON,true,LocalActionStatus.DENIED)
        if(action.status==LocalActionStatus.HANDLED)return record(call,pending,action,HANDLED_BY_PERSON,false,LocalActionStatus.HANDLED)
        if(action.status!=LocalActionStatus.APPROVED)return record(call,pending,action,OUTCOME_UNKNOWN,true,LocalActionStatus.UNCERTAIN)
        if(!unchanged(action,connection,tool))return record(call,pending,action,notDoneOnRecheck("the connection, credential, tool, grant or Bot status changed."),true,LocalActionStatus.CANCELLED)
        val currentPolicy=if(action.automatic)action.policy?:LocalPolicyOutcome(PolicyBehaviour.ASK,"default","The saved policy could not be restored.") else policy.decide(pendingCandidate(action),pending.conversationId)
        if(currentPolicy.behaviour==PolicyBehaviour.HAND_OFF||action.automatic&&currentPolicy.behaviour!=PolicyBehaviour.ALLOW)
            return record(call,pending,action,notDoneOnRecheck(currentPolicy.reason),true,LocalActionStatus.CANCELLED)
        var started=false
        val session=sessions(connection,connection.credentialRef?.let(secrets::token))
        try {
            session.initialize()
            val fresh=binding(action.botId,call.name)
            if(fresh==null||!unchanged(action,fresh.first,fresh.second))return record(call,pending,action,notDoneOnRecheck("access changed before execution."),true,LocalActionStatus.CANCELLED)
            if(!store.beginApprovedAction(action.id))return record(call,pending,action,NOT_DONE,true,LocalActionStatus.CANCELLED)
            started=true
            val result=session.call(tool,call.arguments)
            val failed=result["isError"]?.jsonPrimitive?.booleanOrNull==true
            return record(call,pending,action,result.toString(),failed,if(failed)LocalActionStatus.FAILED else LocalActionStatus.EXECUTED)
        }catch(failure:CancellationException) {
            record(call,pending,action,if(started)OUTCOME_UNKNOWN else NOT_DONE,true,if(started)LocalActionStatus.UNCERTAIN else LocalActionStatus.CANCELLED)
            throw failure
        }catch(failure:Exception) {
            return record(call,pending,action,if(started)OUTCOME_UNKNOWN else "Not done: ${failure.message.orEmpty().take(400)}",true,if(started)LocalActionStatus.UNCERTAIN else LocalActionStatus.FAILED)
        }finally{withContext(NonCancellable){runCatching{session.close()}}}
    }
    private fun connectionName(id:String)=store.connections().find{it.id==id}?.name?:"Connected app"
    private fun refusal(message:String)=buildJsonObject{put("isError",true);put("message",message)}.toString()
    private fun record(call:ToolCall,pending:LocalMessage,action:PendingLocalAction?,result:String,failed:Boolean,status:LocalActionStatus?=null):String {
        val db=store.writableDatabase;db.beginTransaction()
        try {
            store.saveToolEvent(LocalToolEvent(UUID.randomUUID().toString(),pending.id,pending.conversationId,call.id,call.name,call.arguments,result,failed,Instant.now().toString(),action?.let{"${it.connectionName?:connectionName(it.connectionId)} · ${it.tool.name.replace('_',' ')}"}))
            if(action!=null&&status!=null)store.savePendingAction(action.copy(status=status))
            db.setTransactionSuccessful()
        }finally{db.endTransaction();LocalDatabaseChanges.changed()}
        return result
    }
}

package ai.openbot.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import ai.openbot.android.*
import ai.openbot.core.*

/** Same end-aligned muted user bubbles and full-width ghost replies as chat-transcript.tsx. */
@Composable internal fun ConversationScreen(state:AppState,bot:LocalBot?,vm:OpenBotViewModel) {
    val p=LocalOpenBotPalette.current
    var text by rememberSaveable(state.selectedConversation?:state.draftBotId){mutableStateOf("")}
    val scroll=rememberLazyListState()
    val attach=rememberAttachmentPicker(vm)
    val backgroundTurn=state.messages.lastOrNull{it.state==MessageState.GENERATING}?.takeIf{message->message.id in state.continuingTurns}?.id
    val question=state.questions.firstOrNull{it.channelId==state.selectedConversation}
    val stop:(()->Unit)?=if(state.generatingChannelId==state.selectedConversation&&state.generatingChannelId!=null)vm::stopGeneration else backgroundTurn?.let{id->{vm.stopContinuation(id)}}
    LaunchedEffect(state.messages.size){if(state.messages.isNotEmpty())scroll.animateScrollToItem(state.messages.lastIndex)}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(bot?.paused==true)Text("This Bot is paused. Resume it before sending a message.",color=p.mutedForeground)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("transcript"),state=scroll,contentPadding=PaddingValues(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            items(state.messages,key={it.id}) {message->
                val events=state.toolEvents.filter{it.turnId==message.id}
                if(events.isNotEmpty())ToolActivity(events,state)
                state.pendingActions.filter{it.turnId==message.id&&it.status==LocalActionStatus.UNCERTAIN}.forEach{UnknownActionCard(it,state,vm)}
                val firing=readFiring(message.text)
                if(message.role=="user"&&message.systemLabel!=null) {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){message.systemLabel?.let{Text(it,color=p.mutedForeground,style=MaterialTheme.typography.bodySmall)};message.displayText?.let{Text(it,color=p.mutedForeground)}}
                } else if(message.role=="user"&&firing==null) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        Surface(color=p.muted.copy(alpha=.5f),shape=RoundedCornerShape(12.dp),modifier=Modifier.widthIn(max=560.dp)) {
                            Column {
                            AttachmentStrip(message.attachmentIds.mapNotNull{id->state.conversationAttachments.find{it.id==id}},vm,enabled=!state.busy)
                            if(message.text.isNotBlank())SelectionContainer{Text(message.displayText?:message.text,Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium)}
                            }
                        }
                    }
                } else if(message.state==MessageState.GENERATING) {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(message.text.isNotBlank()&&message.text!="Working…")SelectionContainer{MarkdownBody(message.text)}
                        WorkingIndicator(state.stopping)
                    }
                }
                else if(message.state==MessageState.WAITING) {
                    val pending=state.pendingActions.filter{it.turnId==message.id&&it.status==LocalActionStatus.PENDING}
                    pending.forEach{ApprovalCard(it,state,vm)}
                    state.questions.filter{it.turnId==message.id}.forEach{QuestionCard(it,state,vm)}
                    if(pending.isEmpty()&&state.questions.none{it.turnId==message.id})Text("Continuing after your decision…",color=p.mutedForeground)
                    OpenBotTextButton(onClick={vm.stopContinuation(message.id)},enabled=!state.busy){Text("Stop this turn")}
                }
                else if(message.state in listOf(MessageState.FAILED,MessageState.INTERRUPTED))Text(message.text,color=p.destructive)
                else if(firing!=null)Text("Routine · $firing",color=p.mutedForeground)
                else SelectionContainer{MarkdownBody(message.text)}
            }
        }
        SourceComposer(text,{text=it},{if(question!=null)vm.answerQuestion(question.id,text){text=""} else vm.send(text){text=""}},!state.busy&&!state.attachmentsLoading&&bot?.paused==false&&(question!=null||state.messages.none{it.state in listOf(MessageState.WAITING,MessageState.GENERATING)}),compact=true,onStop=stop,stopping=state.stopping,hasAttachments=question==null&&state.draftAttachments.isNotEmpty(),onAttach=if(question==null)attach else null,attachments={if(question==null)AttachmentStrip(state.draftAttachments,vm,removable=true,enabled=!state.busy)})
        Spacer(Modifier.height(4.dp))
    }
}

@Composable private fun WorkingIndicator(stopping:Boolean) {
    val transition=rememberInfiniteTransition(label="working")
    val opacity by transition.animateFloat(initialValue=.45f,targetValue=1f,
        animationSpec=infiniteRepeatable(tween(1200,easing=LinearEasing),RepeatMode.Reverse),label="working opacity")
    Text(if(stopping)"Stopping…" else "Working…",color=LocalOpenBotPalette.current.mutedForeground,modifier=Modifier.alpha(opacity))
}

@Composable private fun ToolActivity(events:List<LocalToolEvent>,state:AppState) {
    val p=LocalOpenBotPalette.current
    fun label(event:LocalToolEvent):String {
        event.displayName?.let{return it}
        for(connection in state.connections)for(tool in connection.tools)if(mcpModelToolName(connection.id,tool.name)==event.name)return "${connection.name} · ${tool.name.replace('_',' ')}"
        return if(event.name.startsWith("mcp_"))"Connected action" else event.name.replace('_',' ')
    }
    var expanded by rememberSaveable(events.first().id){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("${if(expanded)"▾" else "▸"} ${if(events.size==1)label(events.first()) else "${events.size} actions"}",
            Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(vertical=6.dp),style=MaterialTheme.typography.bodyMedium,color=p.mutedForeground)
        if(expanded)events.forEach{event->
            var detail by rememberSaveable(event.id){mutableStateOf(false)}
            Text("${if(detail)"▾" else "▸"} ${label(event)}${when(event.result.trim()){OUTCOME_UNKNOWN->", outcome unknown";WITHDRAWN_APPROVALS_OFF,WITHDRAWN_RULE_ALLOWS->", withdrawn";DECLINED_BY_PERSON->", declined";HANDLED_BY_PERSON->", done by you";else->if(event.failed)", didn't work" else ""}}",Modifier.fillMaxWidth().clickable{detail=!detail}.padding(start=16.dp,top=6.dp,bottom=6.dp),color=if(event.failed&&event.result !in listOf(WITHDRAWN_APPROVALS_OFF,WITHDRAWN_RULE_ALLOWS))p.destructive else p.mutedForeground)
            if(detail)SelectionContainer {Column(Modifier.fillMaxWidth().padding(start=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(event.arguments.toString(),style=MaterialTheme.typography.bodySmall)
                Text(outcomeForPerson(event.result),style=MaterialTheme.typography.bodySmall)
            }}
        }
    }
}

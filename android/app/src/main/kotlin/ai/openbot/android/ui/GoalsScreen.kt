package ai.openbot.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.openbot.android.AppState
import ai.openbot.android.OpenBotViewModel
import ai.openbot.android.R
import ai.openbot.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun GoalsScreen(state:AppState,vm:OpenBotViewModel,botId:String) {
    val p=LocalOpenBotPalette.current
    var creating by rememberSaveable{mutableStateOf(false)}
    var selectedId by rememberSaveable{mutableStateOf<String?>(null)}
    var history by rememberSaveable(selectedId){mutableStateOf(false)}
    val selected=state.goals.find{it.id==selectedId}
    val rows=state.goals.filter{it.botId==botId}
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("goals-list"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=32.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Title("Goals");OpenBotTextButton(onClick={creating=true},enabled=!state.busy){Text("+ New goal")}}}
            item{Text("Work you give this Bot to keep doing shows up here.",color=p.mutedForeground)}
            if(rows.isEmpty())item{SourceEmpty("No goals yet","An ongoing goal. The Bot works on it in the conversation you pick.",Modifier.height(180.dp))}
            items(rows,key={it.id}){goal->
                Row(Modifier.fillMaxWidth().clickable{selectedId=goal.id}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TablerIcon(if(goal.status==GoalStatus.PAUSED)R.drawable.ic_player_play else if(goal.status==GoalStatus.COMPLETED)R.drawable.ic_check else R.drawable.ic_bolt)
                    Column(Modifier.weight(1f)){Text(goal.title,style=MaterialTheme.typography.titleSmall);Text(goalSummary(goal,state.routines),color=p.mutedForeground)}
                    TablerIcon(R.drawable.ic_chevron_right)
                }
                HorizontalDivider(color=p.border)
            }
        }
        if(creating)GoalEditor(state,botId,null,{creating=false},{channel,title,instruction,criteria->vm.saveGoal(null,botId,channel,title,instruction,criteria){creating=false}},onMessage={vm.newConversation(botId)})
        selected?.let{goal->
            GoalEditor(state,botId,goal,{selectedId=null;vm.loadGoalHistory(null)},{channel,title,instruction,criteria->vm.saveGoal(goal.id,botId,channel,title,instruction,criteria){selectedId=null;vm.loadGoalHistory(null)}},
                actions={
                    Text(goalSummary(goal,state.routines),color=p.mutedForeground)
                    if(goal.status!=GoalStatus.COMPLETED)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)){Text("Active",style=MaterialTheme.typography.titleSmall);Text(if(goal.status==GoalStatus.ACTIVE)"Its routines start it, or you can run it now." else "Paused. Nothing starts it until you resume it.",style=MaterialTheme.typography.bodySmall,color=p.mutedForeground)}
                        Switch(goal.status==GoalStatus.ACTIVE,{vm.setGoalStatus(goal,if(it)GoalStatus.ACTIVE else GoalStatus.PAUSED)},enabled=!state.busy,modifier=Modifier.semantics{contentDescription="Goal active"})
                    }
                    if(goal.status==GoalStatus.ACTIVE)OpenBotOutlineButton(onClick={history=true;vm.runGoal(goal)},enabled=!state.busy){Text("Run now")}
                    if(goal.status!=GoalStatus.COMPLETED)OpenBotTextButton(onClick={vm.setGoalStatus(goal,GoalStatus.COMPLETED)},enabled=!state.busy){Text("Complete")}
                    OpenBotTextButton(onClick={vm.openConversation(goal.channelId)}){Text("Open conversation")}
                },details={
                    if(goal.progress.isNotBlank()){Text("Progress",style=MaterialTheme.typography.titleSmall);MarkdownBody(goal.progress)}
                    goal.lastResult?.takeIf{it.isNotBlank()}?.let{Text("Last result",style=MaterialTheme.typography.titleSmall);MarkdownBody(it)}
                    OpenBotTextButton(onClick={history=!history;vm.loadGoalHistory(if(history)goal.id else null)}){Text(if(history)"Hide runs" else "Run history")}
                    if(history&&state.goalHistoryFor==goal.id) {
                        if(state.goalRuns.isEmpty())Text("No runs yet.",style=MaterialTheme.typography.bodySmall,color=p.mutedForeground)
                        state.goalRuns.forEach{run->
                            HorizontalDivider(color=p.border)
                            Text("${run.status.name.lowercase().replaceFirstChar{it.uppercase()}} · ${goalTime(run.startedAt?:run.createdAt)}",style=MaterialTheme.typography.bodySmall)
                            run.error?.let{Text(it,color=p.destructive,style=MaterialTheme.typography.bodySmall)}
                            run.replyText?.let{MarkdownBody(it)}
                        }
                    }
                })
        }
    }
}

@Composable private fun GoalEditor(state:AppState,botId:String,goal:LocalGoal?,onClose:()->Unit,
    onSave:(String,String,String,String)->Unit,onMessage:(()->Unit)?=null,actions:(@Composable ColumnScope.()->Unit)?=null,details:(@Composable ColumnScope.()->Unit)?=null) {
    var title by rememberSaveable(goal?.id){mutableStateOf(goal?.title.orEmpty())}
    var instruction by rememberSaveable(goal?.id){mutableStateOf(goal?.instruction.orEmpty())}
    var criteria by rememberSaveable(goal?.id){mutableStateOf(goal?.successCriteria.orEmpty())}
    var channel by rememberSaveable(goal?.id){mutableStateOf(goal?.channelId.orEmpty())}
    var choosing by remember{mutableStateOf(false)}
    val channels=state.conversations.filter{it.botId==botId}
    val p=LocalOpenBotPalette.current
    SourceDialog(if(goal==null)"New goal" else goal.title,onClose) {
        if(goal==null) {
            Text("An ongoing goal. The Bot works on it in the conversation you pick.",color=p.mutedForeground)
            Text("Conversation",style=MaterialTheme.typography.bodyMedium)
            Box {
                Row(Modifier.fillMaxWidth().border(1.dp,p.border,MaterialTheme.shapes.medium).clickable(enabled=!state.busy&&channels.isNotEmpty()){choosing=true}.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(channels.find{it.id==channel}?.let(::conversationTitle)?:"Choose a conversation",Modifier.weight(1f));TablerIcon(R.drawable.ic_chevron_down)
                }
                DropdownMenu(choosing,{choosing=false}){channels.forEach{item->DropdownMenuItem(text={Text(conversationTitle(item))},onClick={channel=item.id;choosing=false})}}
            }
            if(channels.isEmpty()) {
                Text("Start a conversation with this Bot first.",color=p.mutedForeground)
                OpenBotTextButton(onClick={onClose();onMessage?.invoke()},enabled=!state.busy){Text("Message this Bot")}
            }
        }
        actions?.invoke(this)
        SourceTextField(title,{if(it.length<=160)title=it},label={Text("Title")},singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
        SourceTextField(instruction,{if(it.length<=6000)instruction=it},label={Text("Instruction")},minLines=3,maxLines=10,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
        SourceTextField(criteria,{if(it.length<=3000)criteria=it},label={Text("Success criteria")},placeholder={Text("The report contains the current quarter's graph and a source link.")},minLines=2,maxLines=8,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
        details?.invoke(this)
        Row{OpenBotTextButton(onClick=onClose,enabled=!state.busy){Text("Cancel")};OpenBotButton(onClick={onSave(channel,title,instruction,criteria)},enabled=!state.busy&&channel.isNotEmpty()&&title.isNotBlank()&&instruction.isNotBlank()&&criteria.isNotBlank(),modifier=Modifier.testTag("save-goal")){Text(if(goal==null)"Create goal" else "Save")}}
    }
}
private fun goalSummary(goal:LocalGoal,routines:List<LocalRoutine>):String=when(goal.status) {
    GoalStatus.COMPLETED->"Completed"
    GoalStatus.PAUSED->"Paused · Nothing starts it until you resume it"
    GoalStatus.ACTIVE->"Active · "+if(whyGoalRepeats(goal,routines)=="a routine runs it")"A routine runs it" else if(goal.recurring)"Repeating goal" else "Runs when asked"
}
private fun goalTime(value:String)=DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))

package ai.openbot.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.openbot.android.*
import ai.openbot.android.R
import ai.openbot.core.*

@Composable internal fun BotsScreen(state:AppState,vm:OpenBotViewModel) {
    val p=LocalOpenBotPalette.current
    var editing by remember {mutableStateOf<LocalBot?>(null)}
    var adding by rememberSaveable {mutableStateOf(false)}
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var rulesFor by rememberSaveable{mutableStateOf<String?>(null)}
    var goalsFor by rememberSaveable{mutableStateOf<String?>(null)}
    var routinesFor by rememberSaveable{mutableStateOf<String?>(null)}
    var deleting by remember {mutableStateOf<LocalBot?>(null)}
    val selected=state.bots.find {it.id==selectedId}
    if(rulesFor!=null) {
        BackHandler{rulesFor=null}
        ApprovalRulesScreen(state,vm,botId=requireNotNull(rulesFor))
        return
    }
    if(goalsFor!=null) {
        BackHandler{goalsFor=null;vm.loadGoalHistory(null)}
        GoalsScreen(state,vm,botId=requireNotNull(goalsFor))
        return
    }
    if(routinesFor!=null) {
        BackHandler{routinesFor=null}
        RoutinesScreen(state,vm,botId=routinesFor)
        return
    }
    if(adding||editing!=null) {
        BackHandler {adding=false;editing=null}
        BotEditor(editing,state.busy,{adding=false;editing=null}){name,instructions->vm.saveBot(editing?.id,name,instructions){adding=false;editing=null}}
        return
    }
    if(selected!=null) {
        BackHandler {selectedId=null}
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("bot-profile"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {OpenBotTextButton(onClick={selectedId=null}){Text("‹ Bots")};Title(selected.name)}
            item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){BotAvatar(selected.name,selected.id,40.dp);Column{Text(selected.title,style=MaterialTheme.typography.titleSmall);Text(selected.instruction,color=p.mutedForeground)}}}
            item {Spacer(Modifier.height(16.dp));Text("For you",style=MaterialTheme.typography.titleSmall)}
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)){Text("Active",style=MaterialTheme.typography.titleSmall);Text(if(selected.paused)"Paused. Nothing runs for you until you turn it back on." else "Works on its goals and routines for you.",color=p.mutedForeground)}
                    Switch(!selected.paused,{vm.setPaused(selected,!it)},enabled=!state.busy,modifier=Modifier.semantics{contentDescription="Active"})
                }
                HorizontalDivider(color=p.border)
            }
            item {Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy&&!selected.paused){vm.newConversation(selected.id)}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Message ${selected.name}",style=MaterialTheme.typography.titleSmall);Text("Start a conversation.",color=p.mutedForeground)};TablerIcon(R.drawable.ic_chevron_right)}}
            item {Row(Modifier.fillMaxWidth().clickable{goalsFor=selected.id}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Goals",style=MaterialTheme.typography.titleSmall);Text("Lasting work toward an outcome you name.",color=p.mutedForeground)};TablerIcon(R.drawable.ic_chevron_right)}}
            item {Spacer(Modifier.height(16.dp));Text("Settings for this Bot",style=MaterialTheme.typography.titleSmall)}
            item {Row(Modifier.fillMaxWidth().clickable{routinesFor=selected.id}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Routines",style=MaterialTheme.typography.titleSmall);Text("Manage this Bot’s scheduled routines.",color=p.mutedForeground)};TablerIcon(R.drawable.ic_chevron_right)}}
            item {Row(Modifier.fillMaxWidth().clickable{rulesFor=selected.id}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Rules",style=MaterialTheme.typography.titleSmall);Text("What this Bot does before using a tool or app.",color=p.mutedForeground)};TablerIcon(R.drawable.ic_chevron_right)}}
            item {OpenBotTextButton(onClick={editing=selected},enabled=!state.busy){Text("Setup")}}
            item {Spacer(Modifier.height(16.dp));Text("Manage",style=MaterialTheme.typography.titleSmall)}
            item {
                Column {
                    OpenBotTextButton(onClick={vm.setBotPinned(selected)},enabled=!state.busy){Text(if(selected.pinned)"Unpin" else "Pin")}
                    OpenBotTextButton(onClick={vm.setBotHidden(selected);selectedId=null},enabled=!state.busy){Text(if(selected.hidden)"Unhide" else "Hide")}
                    OpenBotTextButton(onClick={vm.duplicateBot(selected)},enabled=!state.busy){Text("Duplicate")}
                    OpenBotTextButton(onClick={deleting=selected},enabled=!state.busy){Text("Delete Bot",color=p.destructive)}
                }
            }
        }
        deleting?.let {bot->SourceDialog("Delete ${bot.name}?",{deleting=null}) {
            Text("This removes the Bot and its conversations from this device.")
            Row {OpenBotTextButton(onClick={deleting=null}){Text("Cancel")};OpenBotButton(onClick={vm.deleteBot(bot){deleting=null;selectedId=null}},enabled=!state.busy){Text("Delete Bot")}}
        }}
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("bots-list"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=32.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Title("Bots");OpenBotTextButton(onClick={adding=true},enabled=!state.busy){Text("+ New Bot")}};Text("Manage your Bots and access those shared with you.",color=p.mutedForeground)}
        val visible=state.bots.filterNot{it.hidden}
        val pinned=visible.filter{it.pinned}
        if(pinned.isNotEmpty())item{Spacer(Modifier.height(16.dp));Text("Pinned",style=MaterialTheme.typography.titleSmall)}
        items(pinned,key={it.id}){bot->RosterRow(bot){selectedId=bot.id}}
        item{Spacer(Modifier.height(16.dp));Text("Yours",style=MaterialTheme.typography.titleSmall)}
        items(visible.filterNot{it.pinned},key={it.id}){bot->RosterRow(bot){selectedId=bot.id}}
        if(visible.none{!it.pinned})item{SourceEmpty(if(pinned.isEmpty())"No Bots yet" else "All pinned",if(pinned.isEmpty())"Bots you create show up here." else "Your Bots are all pinned above.")}
        item{Spacer(Modifier.height(16.dp));Text("Shared with you",style=MaterialTheme.typography.titleSmall);Spacer(Modifier.height(16.dp));SourceEmpty("Nothing shared yet","Bots somebody shares with you show up here.")}
        val hidden=state.bots.filter{it.hidden}
        if(hidden.isNotEmpty())item{Text("Hidden",style=MaterialTheme.typography.titleSmall)}
        items(hidden,key={it.id}){bot->RosterRow(bot){selectedId=bot.id}}
    }
}

@Composable private fun RosterRow(bot:LocalBot,onOpen:()->Unit) {
    val p=LocalOpenBotPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        BotAvatar(bot.name,bot.id)
        Column(Modifier.weight(1f)){Text(bot.name,style=MaterialTheme.typography.titleSmall);Text(if(bot.paused)"Paused" else bot.title,color=p.mutedForeground)}
        TablerIcon(R.drawable.ic_chevron_right)
    }
    HorizontalDivider(color=p.border)
}

@Composable private fun BotEditor(bot:LocalBot?,busy:Boolean,onClose:()->Unit,onSave:(String,String)->Unit) {
    var name by rememberSaveable(bot?.id){mutableStateOf(bot?.name.orEmpty())}
    var instructions by rememberSaveable(bot?.id){mutableStateOf(bot?.instruction.orEmpty())}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Title(if(bot==null)"New Bot" else "Setup")
        SourceTextField(name,{if(it.length<=80)name=it},label={Text("Name")},placeholder={Text("Expense Review")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        SourceTextField(instructions,{if(it.length<=1000)instructions=it},label={Text("Instructions")},placeholder={Text("Review receipts, categorize expenses, and prepare reimbursement reports.")},minLines=5,maxLines=12,enabled=!busy,modifier=Modifier.fillMaxWidth())
        if(bot==null)Text("Optional",style=MaterialTheme.typography.bodySmall,color=LocalOpenBotPalette.current.mutedForeground)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){OpenBotTextButton(onClick=onClose,enabled=!busy){Text("Cancel")};OpenBotButton(onClick={onSave(name,instructions)},enabled=!busy&&name.isNotBlank()){Text(if(bot==null)"Create Bot" else "Save")}}
    }
}

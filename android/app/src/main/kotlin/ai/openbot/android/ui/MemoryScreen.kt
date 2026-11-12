package ai.openbot.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.openbot.android.*
import ai.openbot.core.*

/** Native translation of components/memory/memories.tsx, including its remember/edit dialogs. */
@Composable internal fun MemoryScreen(state:AppState,vm:OpenBotViewModel,capabilities:Boolean=false) {
    val p=LocalOpenBotPalette.current
    var adding by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<Memory?>(null)}
    var draft by remember {mutableStateOf("")}
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("memory-list"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=32.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Your memories",style=MaterialTheme.typography.titleSmall)
                    OpenBotTextButton(onClick={draft="";adding=true},enabled=!state.busy){Text("+ Remember a fact")}
                }
                Text("Manage what your Bots remember about you. Facts you add are shared with all Bots; learned facts stay with the Bot that learned them.",color=p.mutedForeground)
            }
            if(state.memories.isEmpty())item {SourceEmpty("No memories yet","Add one with Remember a fact.",Modifier.height(180.dp))}
            items(state.memories,key={it.id}) {memory->
                Row(Modifier.fillMaxWidth().padding(vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(memory.content,Modifier.testTag("memory-content"),style=MaterialTheme.typography.titleSmall,color=if(memory.enabled)p.foreground else p.mutedForeground)
                        val status=if(!memory.enabled)"Turned off" else if(memory.formedBy==FormedBy.BOT)"Formed by your Bot${if(memory.reviewState==ReviewState.UNREVIEWED)" · needs review" else ""}" else if(memory.reviewState==ReviewState.UNREVIEWED)"Imported · needs review" else "Reviewed"
                        Text("${memory.provenance} · $status",color=p.mutedForeground)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OpenBotOutlineButton(onClick={draft=memory.content;editing=memory},enabled=!state.busy,shape=MaterialTheme.shapes.small){Text("Edit")}
                            if(memory.reviewState==ReviewState.UNREVIEWED)OpenBotTextButton(onClick={vm.confirmMemory(memory)},enabled=!state.busy){Text("Confirm")}
                            OpenBotTextButton(onClick={vm.deleteMemory(memory.id)},enabled=!state.busy){Text("Forget")}
                        }
                    }
                    Switch(memory.enabled,{vm.setMemoryEnabled(memory,it)},enabled=!state.busy,modifier=Modifier.semantics{contentDescription="Use this memory"})
                }
                HorizontalDivider(color=p.border)
            }
        }
        if(adding||editing!=null)SourceDialog(if(adding)"Remember a fact" else "Edit memory",{if(!state.busy){adding=false;editing=null}}) {
            if(adding)Text("All your Bots will know it.",color=p.mutedForeground)
            SourceTextField(draft,{if(it.length<=6000)draft=it},label={Text(if(adding)"Something you want your Bots to know" else "Memory")},
                placeholder={Text("I prefer meetings in the morning.")},minLines=3,maxLines=8,enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                OpenBotTextButton(onClick={adding=false;editing=null},enabled=!state.busy){Text("Cancel")}
                OpenBotButton(onClick={vm.saveMemory(editing?.id,draft){draft="";adding=false;editing=null}},enabled=!state.busy&&draft.isNotBlank()){Text(if(adding)"Remember" else "Save")}
            }
        }
    }
}

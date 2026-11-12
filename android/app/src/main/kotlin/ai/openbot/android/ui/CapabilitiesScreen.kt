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
import androidx.compose.ui.unit.dp
import ai.openbot.android.*
import ai.openbot.core.*

@Composable internal fun CapabilitiesScreen(state: AppState,vm: OpenBotViewModel) {
    var tab by rememberSaveable {mutableStateOf("Skills")}
    var creating by rememberSaveable {mutableStateOf(false)}
    var editing by remember {mutableStateOf<LocalSkill?>(null)}
    if(creating||editing!=null) {
        BackHandler {creating=false;editing=null}
        SkillEditor(editing,state.busy,{creating=false;editing=null}) {skill->vm.saveSkill(skill,creating){creating=false;editing=null}}
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=32.dp)) {
            Title("Capabilities")
            Row(Modifier.padding(top=24.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("Skills","Memory").forEach {label->
                    if(label==tab) FilledTonalButton(onClick={tab=label},shape=MaterialTheme.shapes.small){Text(label)}
                    else OpenBotTextButton(onClick={tab=label}){Text(label)}
                }
            }
        }
        if(tab=="Memory") MemoryScreen(state,vm,capabilities=true)
        else LazyColumn(Modifier.fillMaxSize().testTag("skills-list"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=32.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Your skills",style=MaterialTheme.typography.titleSmall)
                    OpenBotTextButton(onClick={creating=true},enabled=!state.busy){Text("+ New skill")}
                }
                Text("Type / and a skill's name to give a Bot that instruction. Only you see yours, and they work on Bots you own.",color=LocalOpenBotPalette.current.mutedForeground)
            }
            if(state.skills.isEmpty())item{SourceEmpty("No skills yet. Write one with New skill.",modifier=Modifier.height(180.dp))}
            items(state.skills,key={it.slug}) {skill->
                Column(Modifier.fillMaxWidth().clickable(enabled=!state.busy){editing=skill}.padding(vertical=14.dp).testTag("skill-${skill.slug}")) {
                    Text(skill.title,style=MaterialTheme.typography.titleSmall)
                    if(skill.summary.isNotBlank())Text(skill.summary,color=LocalOpenBotPalette.current.mutedForeground)
                    Text("/${skill.slug}",style=MaterialTheme.typography.bodySmall,color=LocalOpenBotPalette.current.mutedForeground)
                }
                HorizontalDivider(color=LocalOpenBotPalette.current.border)
            }
        }
    }
}

@Composable private fun SkillEditor(skill:LocalSkill?,busy:Boolean,onClose:()->Unit,onSave:(LocalSkill)->Unit) {
    var slug by rememberSaveable(skill?.slug){mutableStateOf(skill?.slug.orEmpty())}
    var title by rememberSaveable(skill?.slug){mutableStateOf(skill?.title.orEmpty())}
    var summary by rememberSaveable(skill?.slug){mutableStateOf(skill?.summary.orEmpty())}
    var instructions by rememberSaveable(skill?.slug){mutableStateOf(skill?.instructions.orEmpty())}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Title(if(skill==null)"New skill" else "Edit skill")
        Text("An instruction you start with /. Only you see it, and it works on Bots you own.",color=LocalOpenBotPalette.current.mutedForeground)
        SourceTextField(slug,{if(it.length<=40)slug=it},label={Text("Command")},singleLine=true,enabled=!busy&&skill==null,modifier=Modifier.fillMaxWidth())
        SourceTextField(title,{if(it.length<=120)title=it},label={Text("Title")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        SourceTextField(summary,{if(it.length<=200)summary=it},label={Text("One-liner")},enabled=!busy,modifier=Modifier.fillMaxWidth())
        SourceTextField(instructions,{instructions=it},label={Text("Instructions")},minLines=5,maxLines=12,enabled=!busy,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OpenBotTextButton(onClick=onClose,enabled=!busy){Text("Cancel")}
            OpenBotButton(onClick={onSave(LocalSkill(slug.trim(),title.trim(),summary.trim(),instructions.trim(),skill?.tools.orEmpty()))},enabled=!busy&&slug.isNotBlank()&&title.isNotBlank()&&instructions.isNotBlank()){Text("Save skill")}
        }
    }
}

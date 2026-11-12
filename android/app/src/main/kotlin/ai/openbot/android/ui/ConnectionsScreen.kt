package ai.openbot.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.openbot.android.*
import ai.openbot.android.R
import ai.openbot.core.*

@Composable internal fun ConnectionsScreen(state:AppState,vm:OpenBotViewModel) {
    val p=LocalOpenBotPalette.current
    var selectedId by rememberSaveable{mutableStateOf<String?>(null)}
    var adding by rememberSaveable{mutableStateOf(false)}
    var editing by rememberSaveable{mutableStateOf(false)}
    var deleting by remember{mutableStateOf(false)}
    var expandedBot by rememberSaveable{mutableStateOf<String?>(null)}
    var search by rememberSaveable{mutableStateOf("")}
    val selected=state.connections.find{it.id==selectedId}
    if(adding||editing&&selected!=null) {
        BackHandler{adding=false;editing=false}
        ConnectionEditor(if(adding)null else selected,state.busy,{adding=false;editing=false}){name,url,token,clear->
            vm.connectMcp(if(adding)null else selected?.id,name,url,token,clear){adding=false;editing=false}
        }
        return
    }
    if(selected!=null) {
        BackHandler{selectedId=null;expandedBot=null}
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("connection-profile"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item{Title(selected.name);Text(selected.url,color=p.mutedForeground);Text("Connected · ${selected.serverName?:selected.name}",style=MaterialTheme.typography.bodySmall,color=p.mutedForeground)}
                item{Row {
                    OpenBotTextButton(onClick={editing=true},enabled=!state.busy){Text("Connection settings")}
                    OpenBotTextButton(onClick={vm.connectMcp(selected.id,selected.name,selected.url,null,false){}},enabled=!state.busy){Text("Refresh tools")}
                }}
                item{Text("Bots",style=MaterialTheme.typography.titleSmall);Text("Choose the actions each Bot can use. New actions stay off until you grant them. Approval rules decide when you are asked. Deleting, sending, sharing and publishing always ask first.",color=p.mutedForeground)}
                if(selected.tools.isEmpty())item{SourceEmpty("No actions available","This MCP server did not offer any tools.")}
                items(state.bots,key={it.id}){bot->
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().clickable{expandedBot=if(expandedBot==bot.id)null else bot.id}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            BotAvatar(bot.name,bot.id,32.dp)
                            Column(Modifier.weight(1f)){Text(bot.name,style=MaterialTheme.typography.titleSmall);Text("${selected.grants[bot.id].orEmpty().size} of ${selected.tools.size} actions",style=MaterialTheme.typography.bodySmall,color=p.mutedForeground)}
                            TablerIcon(if(expandedBot==bot.id)R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
                        }
                        if(expandedBot==bot.id)selected.tools.forEach{tool->
                            Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)){Text(tool.name);if(tool.description.isNotBlank())Text(tool.description,style=MaterialTheme.typography.bodySmall,color=p.mutedForeground)}
                                Switch(tool.name in selected.grants[bot.id].orEmpty(),{vm.grantConnectionTool(selected,bot.id,tool.name,it)},enabled=!state.busy,
                                    modifier=Modifier.semantics{contentDescription="Allow ${bot.name} to use ${tool.name}"})
                            }
                        }
                        HorizontalDivider(color=p.border)
                    }
                }
                item{OpenBotTextButton(onClick={deleting=true},enabled=!state.busy){Text("Disconnect",color=p.destructive)}}
            }
            if(deleting)SourceDialog("Disconnect ${selected.name}?",{deleting=false}) {
                Text("Bots will no longer be able to use this connection. Its credential and grants will be removed from this phone.")
                Row{OpenBotTextButton(onClick={deleting=false}){Text("Cancel")};OpenBotButton(onClick={vm.disconnectMcp(selected){deleting=false;selectedId=null}},enabled=!state.busy,modifier=Modifier.testTag("confirm-disconnect")){Text("Disconnect")}}
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("connections-list"),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=32.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Title("Connections");Spacer(Modifier.height(16.dp));Text("Add apps and connect your accounts so your Bots can use them.",color=p.mutedForeground)}
        item {OpenBotTextButton(onClick={adding=true},enabled=!state.busy){Text("+ Add MCP server")}}
        if(state.connections.isEmpty())item{SourceEmpty("No apps yet","Add an MCP server to connect an app.",Modifier.height(200.dp))}
        else {
            item{SourceTextField(search,{search=it},label={Text("Search connections")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            items(state.connections.filter{it.name.contains(search,ignoreCase=true)||it.serverName?.contains(search,ignoreCase=true)==true},key={it.id}){connection->
                Row(Modifier.fillMaxWidth().clickable{selectedId=connection.id}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TablerIcon(R.drawable.ic_plug)
                    Column(Modifier.weight(1f)){Text(connection.name,style=MaterialTheme.typography.titleSmall);Text("Connected · ${connection.tools.size} actions",color=p.mutedForeground)}
                    TablerIcon(R.drawable.ic_chevron_right)
                }
                HorizontalDivider(color=p.border)
            }
        }
    }
}

@Composable private fun ConnectionEditor(connection:LocalConnection?,busy:Boolean,onClose:()->Unit,onSave:(String,String,String?,Boolean)->Unit) {
    var name by rememberSaveable(connection?.id){mutableStateOf(connection?.name.orEmpty())}
    var url by rememberSaveable(connection?.id){mutableStateOf(connection?.url.orEmpty())}
    var token by remember{mutableStateOf("")}
    var clear by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Title(if(connection==null)"Add MCP server" else "Connection settings")
        Text("Connect directly to a Streamable HTTP MCP server. Use its final HTTPS URL and, when required, its bearer token.",color=LocalOpenBotPalette.current.mutedForeground)
        SourceTextField(name,{if(it.length<=100)name=it},label={Text("Name")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        SourceTextField(url,{url=it},label={Text("HTTPS MCP URL")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())
        SourceTextField(token,{token=it},label={Text("Bearer token (optional)")},placeholder={Text(if(connection?.credentialRef!=null)"Leave empty to retain the saved token" else "Token provided by the MCP server")},singleLine=true,enabled=!busy,
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
        if(connection?.credentialRef!=null)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(clear,{clear=it},enabled=!busy);Text("Remove saved credential")}
        Text("Changing the URL clears the old credential and Bot grants. Tokens are encrypted in Android Keystore storage.",style=MaterialTheme.typography.bodySmall,color=LocalOpenBotPalette.current.mutedForeground)
        Row{OpenBotTextButton(onClick=onClose,enabled=!busy){Text("Cancel")};OpenBotButton(onClick={onSave(name,url,token.takeIf{it.isNotBlank()},clear)},enabled=!busy&&name.isNotBlank()&&url.isNotBlank()){Text(if(busy)"Connecting…" else "Connect")}}
    }
}

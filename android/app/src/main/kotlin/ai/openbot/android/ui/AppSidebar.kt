package ai.openbot.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.openbot.android.AppState
import ai.openbot.android.OpenBotViewModel
import ai.openbot.android.R
import ai.openbot.core.*

/** Original app-sidebar.tsx layout: product/search/history followed by primary destinations. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun AppSidebar(state: AppState, vm: OpenBotViewModel, route: String,
    onNavigate: (String) -> Unit, onOpen: (String) -> Unit, onHome: () -> Unit) {
    val p = LocalOpenBotPalette.current
    var search by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<LocalConversation?>(null) }
    var deleting by remember {mutableStateOf<LocalConversation?>(null)}
    val visible = pinnedConversationsFirst(matchingConversations(state.conversations,search))
    Column(Modifier.fillMaxHeight().width(320.dp).background(p.sidebar).padding(8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onHome).padding(8.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.openbot_logo),null,Modifier.size(20.dp))
                Text("OpenBot",fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onHome) { TablerIcon(R.drawable.ic_plus,"New conversation") }
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.background,RoundedCornerShape(8.dp)).border(1.dp,p.border,RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TablerIcon(R.drawable.ic_search,modifier = Modifier.size(16.dp))
            BasicTextField(search,{search=it},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium.copy(color=p.foreground),
                modifier=Modifier.weight(1f).semantics {contentDescription="Search conversations"},decorationBox={inner->Box{if(search.isEmpty())Text("Search…",color=p.mutedForeground);inner()}})
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (visible.isEmpty()) item {
                SourceEmpty(if(search.isBlank()) "No conversations yet" else "No conversations match your search",
                    if(search.isBlank()) "Start a conversation with a Bot and it shows up here." else "No conversation has “${search.trim()}” in its name or latest message.",
                    Modifier.height(360.dp))
            }
            items(visible,key={it.id}) { channel ->
                Box {
                    Row(Modifier.fillMaxWidth().background(if(state.selectedConversation==channel.id)p.sidebarAccent else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(8.dp))
                        .combinedClickable(onClick={onOpen(channel.id)},onLongClick={menu=channel}).padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(channel.pinned)TablerIcon(R.drawable.ic_pin,modifier=Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if(state.preferences.messageListEmphasis=="agent")channel.name else conversationTitle(channel),maxLines=1,fontWeight=if(conversationUnread(channel,state.selectedConversation))FontWeight.SemiBold else FontWeight.Normal)
                            (if(state.preferences.messageListEmphasis=="agent")conversationTitle(channel) else channel.lastMessage)?.let {Text(it,maxLines=1,style=MaterialTheme.typography.bodySmall,color=p.mutedForeground)}
                        }
                    }
                    DropdownMenu(menu?.id==channel.id,{menu=null}) {
                        DropdownMenuItem(text={Text(if(channel.pinned)"Unpin conversation" else "Pin conversation")},onClick={vm.pinConversation(channel);menu=null},enabled=!state.busy)
                        DropdownMenuItem(text={Text("Delete conversation…")},onClick={menu=null;deleting=channel},enabled=!state.busy)
                    }
                }
            }
        }
        HorizontalDivider(color=p.border,modifier=Modifier.padding(vertical=8.dp))
        listOf(Triple("Bots",R.drawable.ic_bolt,"Bots"),Triple("Capabilities",R.drawable.ic_box,"Capabilities"),Triple("Routines",R.drawable.ic_clock,"Routines"),Triple("Connections",R.drawable.ic_plug,"Connections"),Triple("Approvals",R.drawable.ic_shield_lock,"Approvals"),Triple("Settings",R.drawable.ic_settings,"Settings")).forEach {(label,icon,destination)->
            Row(Modifier.fillMaxWidth().height(44.dp).background(if(route==destination)p.sidebarAccent else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(8.dp))
                .clickable {onNavigate(destination)}.padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TablerIcon(icon);Text(label)
            }
        }
        Text(state.preferences.name.ifBlank {"you@openbot.local"},modifier=Modifier.padding(start=10.dp,top=10.dp))
        Text("v${ai.openbot.android.BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.labelSmall,color=p.mutedForeground,modifier=Modifier.padding(start=10.dp,bottom=10.dp))
    }
    deleting?.let{channel->
        SourceDialog("Delete ${channel.name}?",{deleting=null}) {
            Text("It disappears for everyone in the conversation.",color=p.mutedForeground)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                OpenBotTextButton(onClick={deleting=null},enabled=!state.busy){Text("Cancel")}
                OpenBotButton(onClick={vm.deleteConversation(channel){deleting=null}},enabled=!state.busy,modifier=Modifier.testTag("confirm-delete-conversation")){Text(if(state.busy)"Deleting…" else "Delete")}
            }
        }
    }

}

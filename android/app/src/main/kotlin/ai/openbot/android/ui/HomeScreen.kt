package ai.openbot.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.openbot.android.AppState
import ai.openbot.android.OpenBotViewModel
import ai.openbot.core.firstConversationGreeting

/** Native translation of routes/_authed/_app/index.tsx's home state, using its exact copy. */
@Composable internal fun HomeScreen(state: AppState, vm: OpenBotViewModel) {
    val p = LocalOpenBotPalette.current
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val bot = state.bots.find { it.id == chosen } ?: state.bots.firstOrNull { !it.paused }
    val attach=rememberAttachmentPicker(vm)
    var picking by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .heightIn(min = maxHeight), verticalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 672.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(top = 28.dp)) {
                Text(firstConversationGreeting(state.preferences.name,state.conversations.isEmpty()), style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                SourceComposer(state.homeDraft, { vm.setHomeDraft(it); picking = it.endsWith("@") },
                    { bot?.let { vm.startConversation(state.homeDraft,it.id) } }, !state.busy && !state.attachmentsLoading && bot != null,dictationEnabled=state.dictationConfigured,hasAttachments=state.draftAttachments.isNotEmpty(),onAttach=attach,attachments={AttachmentStrip(state.draftAttachments,vm,removable=true,enabled=!state.busy)})
                Box {
                    DropdownMenu(picking, { picking = false }) {
                        state.bots.filterNot { it.paused }.forEach { option ->
                            DropdownMenuItem(text = { Text(option.name) }, onClick = {
                                chosen = option.id; picking = false; vm.setHomeDraft(state.homeDraft.removeSuffix("@"))
                            })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (chosen == null) "OpenBot picks the right Bot. Type @ to choose one." else "Sending to ${bot?.name.orEmpty()}. Type @ to choose another.",
                    style = MaterialTheme.typography.bodySmall, color = p.mutedForeground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(40.dp))
                Text("Explore Bots",style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(16.dp))
                SourceEmpty("Nobody has shared a Bot with you yet.")
            }
        }
    }
}

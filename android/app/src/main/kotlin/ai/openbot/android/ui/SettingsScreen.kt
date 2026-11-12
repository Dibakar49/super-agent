package ai.openbot.android.ui

import ai.openbot.android.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
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

@Composable internal fun SettingsScreen(
    state: AppState,
    vm: OpenBotViewModel,
    onModel: () -> Unit,
    onRules: () -> Unit,
    onNotifications: (Boolean) -> Unit,
    notificationNotice: String? = null,
) {
    val p = LocalOpenBotPalette.current
    var name by rememberSaveable(state.preferences.name) { mutableStateOf(state.preferences.name) }
    var instructions by rememberSaveable(state.preferences.standingInstructions) { mutableStateOf(state.preferences.standingInstructions) }
    var confirmingClear by remember { mutableStateOf(false) }
    val dark = state.preferences.darkTheme ?: isSystemInDarkTheme()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("General")
        Text("Set your name, how OpenBot looks, and the instructions every Bot follows.", color = p.mutedForeground)
        Spacer(Modifier.height(16.dp))
        Text("About you", style = MaterialTheme.typography.titleSmall)
        SourceTextField(name, { if (it.length <= 100)name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OpenBotButton(onClick = { vm.savePreferences(state.preferences.copy(name = name.trim())) }, enabled = !state.busy && name.trim().isNotEmpty()) { Text("Save name") }
        SourceTextField(instructions, { instructions = it }, label = { Text("Standing instructions") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        OpenBotButton(onClick = { vm.savePreferences(state.preferences.copy(standingInstructions = instructions.trim())) }, enabled = !state.busy) { Text("Save instructions") }
        Spacer(Modifier.height(16.dp))
        Text("Appearance", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dark theme", style = MaterialTheme.typography.titleSmall)
                Text("Use the dark appearance across OpenBot.", color = p.mutedForeground)
            }
            Switch(dark, { vm.savePreferences(state.preferences.copy(darkTheme = it)) }, enabled = !state.busy)
        }
        HorizontalDivider(color = p.border)
        Text("Conversation list", style = MaterialTheme.typography.titleSmall)
        Row {
            listOf("thread" to "Conversation titles", "agent" to "Bot names").forEach { (value, label) ->
                OpenBotTextButton(onClick = { vm.savePreferences(state.preferences.copy(messageListEmphasis = value)) }, enabled = !state.busy) { Text("${if (state.preferences.messageListEmphasis == value)"✓ " else ""}$label") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Actions", style = MaterialTheme.typography.titleSmall)
        OpenBotTextButton(onClick = onRules, modifier = Modifier.testTag("settings-approvals")) { Text("Approvals") }
        Spacer(Modifier.height(16.dp))
        Text("Notifications", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bot updates", style = MaterialTheme.typography.titleSmall)
                Text("Replies, questions and approvals from your Bots.", color = p.mutedForeground)
            }
            Switch(state.preferences.notificationsEnabled, onNotifications, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Bot updates" })
        }
        notificationNotice?.let { Text(it, color = p.mutedForeground) }
        Spacer(Modifier.height(16.dp))
        Text("Models", style = MaterialTheme.typography.titleSmall)
        OpenBotTextButton(onClick = onModel) { Text("Model settings") }
        HorizontalDivider(color = p.border)
        Text("Local data", style = MaterialTheme.typography.titleSmall)
        Text("Manage the data saved on this phone.", color = p.mutedForeground)
        OpenBotOutlineButton(onClick = { confirmingClear = true }, enabled = !state.busy, modifier = Modifier.testTag("clear-local-data")) { Text("Clear local data…") }
    }
    if (confirmingClear) {
        SourceDialog("Clear local data?", { if (!state.busy) confirmingClear = false }) {
            Text("Deletes conversations, Bots, memories, skills, goals, routines, attachments, settings and saved API/connection keys from this phone. Imported local models stay installed.", color = p.mutedForeground)
            state.error?.let { Text(it, color = p.destructive) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OpenBotTextButton(onClick = { confirmingClear = false }, enabled = !state.busy) { Text("Cancel") }
                OpenBotButton(
                    onClick = { vm.clearLocalData { confirmingClear = false } },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("confirm-clear-local-data"),
                ) { Text(if (state.busy) "Clearing…" else "Clear local data") }
            }
        }
    }
}
